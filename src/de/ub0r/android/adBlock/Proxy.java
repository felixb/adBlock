/*
 * Copyright (C) 2009 Felix Bechstein
 * 
 * This file is part of AdBlock.
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; If not, see <http://www.gnu.org/licenses/>.
 */
package de.ub0r.android.adBlock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

/**
 * This ad blocking Proxy Service will work as an ordinary HTTP proxy. Set APN's
 * proxy preferences to proxy's connection parameters.
 * 
 * @author Felix Bechstein
 */
public class Proxy extends Service implements Runnable {

	/** Preferences: Port. */
	static final String PREFS_PORT = "port";
	/** Preferences: Filter. */
	static final String PREFS_FILTER = "filter";

	/** HTTP Response: blocked. */
	private static final String HTTP_BLOCK = "HTTP/1.1 500 blocked by AdBlock";
	/** HTTP Response: error. */
	private static final String HTTP_ERROR = "HTTP/1.1 500 error by AdBlock";
	/** HTTP Response: connected. */
	private static final String HTTP_CONNECTED = "HTTP/1.1 200 connected";
	/** HTTP Response: flush. */
	private static final String HTTP_RESPONSE = "\n\n";

	/** Default Port for HTTP. */
	private static final int PORT_HTTP = 80;
	/** Default Port for HTTPS. */
	private static final int PORT_HTTPS = 443;

	/** Proxy. */
	private Thread proxy = null;
	/** Proxy's port. */
	private int port = -1;
	/** Proxy's filter. */
	private ArrayList<String> filter = new ArrayList<String>();
	/** Stop proxy? */
	private boolean stop = false;

	/** Tag for output. */
	private static final String TAG = "AdBlock.Proxy";

	/**
	 * Connection handles a single HTTP Connection. Run this as a Thread.
	 * 
	 * @author Felix Bechstein
	 */
	private class Connection implements Runnable {

		// TODO: cache object.refs
		// TODO: no private object.refs accessed by inner classes
		// TODO: reduce object creation

		/** Local Socket. */
		private final Socket local;
		/** Remote Socket. */
		private Socket remote;
		/** Time to wait for new input. */
		private static final long SLEEP = 100;
		/** Is this connection still running? */
		private boolean running = true;
		/** First run. */
		private boolean firstRun = true;

		/** State: normal. */
		private static final short STATE_NORMAL = 0;
		/** State: closed by local side. */
		private static final short STATE_CLOSED_IN = 1;
		/** State: closed by remote side. */
		private static final short STATE_CLOSED_OUT = 2;
		/** Connections state. */
		private short state = STATE_NORMAL;
		/** Sync state. */
		private Object syncState = new Object();

		/**
		 * CopyStream reads one stream and writes it's data into an other
		 * stream. Run this as a Thread.
		 * 
		 * @author Felix Bechstein
		 */
		private class CopyStream implements Runnable {
			/** Reader. */
			private final InputStream reader;
			/** Writer. */
			private final OutputStream writer;

			/** Size of buffer. */
			private static final short BUFFSIZE = 512;

			/**
			 * Constructor.
			 * 
			 * @param r
			 *            reader
			 * @param w
			 *            writer
			 */
			public CopyStream(final InputStream r, final OutputStream w) {
				this.reader = new BufferedInputStream(r, BUFFSIZE);
				this.writer = new BufferedOutputStream(w, BUFFSIZE);
			}

			/**
			 * Run by Thread.start().
			 */
			@Override
			public void run() {
				byte[] buf = new byte[BUFFSIZE];
				int read = 0;
				try {
					while (true) {
						read = this.reader.available();
						if (read < 1 || read > BUFFSIZE) {
							read = BUFFSIZE;
						}
						read = this.reader.read(buf, 0, read);
						if (read < 0) {
							break;
						}
						this.writer.write(buf, 0, read);
						if (this.reader.available() < 1) {
							this.writer.flush();
						}
					}
					synchronized (Connection.this.syncState) {
						// only change state if old state was normal
						if (Connection.this.state == Connection.STATE_NORMAL) {
							Connection.this.state = Connection.STATE_CLOSED_OUT;
						}
					}
					this.writer.close();
					Connection.this.close();
				} catch (IOException e) {
					Log.e(TAG, null, e);
					Log.d(TAG, new String(buf, 0, read));
				}
			}
		}

		/**
		 * Constructor.
		 * 
		 * @param socket
		 *            local Socket
		 */
		public Connection(final Socket socket) {
			this.local = socket;
		}

		/**
		 * Check if URL is blocked.
		 * 
		 * @param url
		 *            URL
		 * @return if URL is blocked?
		 */
		private boolean checkURL(final String url) {
			if (url.indexOf("admob") >= 0 || url.indexOf("google") >= 0) {
				return false;
			}
			for (String f : Proxy.this.filter) {
				if (url.indexOf(f) >= 0) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Read in HTTP Header. Parse for URL to connect to.
		 * 
		 * @param reader
		 *            buffer reader from which we read the header
		 * @param buffer
		 *            buffer into which the header is written
		 * @return URL to which we should connect, port other than 80 is given
		 *         explicitly
		 * @throws IOException
		 *             inner IOException
		 */
		private URL readHeader(final BufferedReader reader,
				final StringBuilder buffer) throws IOException {
			URL ret = null;
			String[] strings;
			// read first line
			if (this.state == STATE_CLOSED_OUT) {
				return null;
			}
			String line = reader.readLine();
			if (line == null) {
				return null;
			}
			buffer.append(line + "\n");
			strings = line.split(" ");
			if (strings.length > 1) {
				if (strings[0].equals("CONNECT")) {
					String targetHost = strings[1];
					int targetPort = PORT_HTTPS;
					strings = targetHost.split(":");
					if (strings.length > 1) {
						targetPort = Integer.parseInt(strings[1]);
						targetHost = strings[0];
					}
					ret = new URL("https://" + targetHost + ":" + targetPort);
				} else if (strings[0].equals("GET")
						|| strings[1].equals("POST")) {
					String path = null;
					if (strings[1].startsWith("http://")) {
						ret = new URL(strings[1]);
						path = ret.getPath();
					} else {
						path = strings[1];
					}
					// read header
					while (true) {
						line = reader.readLine();
						buffer.append(line + "\n");
						if (line.length() == 0) {
							break;
						}
						if (line.startsWith("Host:")) {
							strings = line.split(" ");
							if (strings.length > 1) {
								ret = new URL("http://" + strings[1] + path);
							}
							break;
						}
					}
				}
			}
			strings = null;

			// copy rest of reader's buffer
			while (reader.ready()) {
				buffer.append(reader.readLine() + "\n");
			}
			return ret;
		}

		/**
		 * Close local and remote socket.
		 * 
		 * @throws IOException
		 *             IOException
		 */
		private void close() throws IOException {
			Log.d(TAG, "close()");
			// this.running = false;
			Socket mSocket = this.remote;
			if (mSocket != null) {
				synchronized (mSocket) {
					if (mSocket.isConnected()) {
						try {
							mSocket.shutdownInput();
							mSocket.shutdownOutput();
						} catch (IOException e) {
							Log.e(TAG, null, e);
						}
						mSocket.close();
					}
				}
				this.remote = null;
			}
			mSocket = this.local;
			if (this.state == STATE_CLOSED_OUT) {
				synchronized (mSocket) {
					if (mSocket.isConnected()) {
						try {
							mSocket.shutdownOutput();
							mSocket.shutdownInput();
						} catch (IOException e) {
							Log.e(TAG, null, e);
						}
						mSocket.close();
					}
				}
			}
		}

		/**
		 * Run by Thread.start().
		 */
		@Override
		public void run() {
			InputStream lInStream;
			OutputStream lOutStream;
			BufferedReader lReader;
			BufferedWriter lWriter;
			try {
				lInStream = this.local.getInputStream();
				lOutStream = this.local.getOutputStream();
				lReader = new BufferedReader(new InputStreamReader(lInStream),
						CopyStream.BUFFSIZE);
				lWriter = new BufferedWriter(
						new OutputStreamWriter(lOutStream), CopyStream.BUFFSIZE);
			} catch (IOException e) {
				Log.e(TAG, null, e);
				return;
			}
			try {
				InputStream rInStream = null;
				OutputStream rOutStream = null;
				BufferedWriter remoteWriter = null;
				Thread rThread = null;
				StringBuilder buffer = new StringBuilder();
				boolean block = false;
				String tHost = null;
				int tPort = -1;
				URL url;
				boolean connectSSL = false;
				while (this.running && this.local.isConnected()) {
					buffer = new StringBuilder();
					url = this.readHeader(lReader, buffer);
					if (buffer.length() == 0) {
						break;
					}
					if (this.local.isConnected() && rThread != null
							&& !rThread.isAlive()) {
						rThread.join();
						tHost = null;
						if (connectSSL) {
							this.local.close();
						}
						Socket mSocket = this.remote;
						if (mSocket != null) {
							synchronized (mSocket) {
								Log.d(TAG, "close dead remote");
								mSocket.shutdownInput();
								mSocket.shutdownOutput();
								mSocket.close();
								this.remote = null;
							}
						}
						rInStream = null;
						rOutStream = null;
						rThread = null;
					}
					if (url != null) {
						block = this.checkURL(url.toString());
						Log.d(TAG, "new url: " + url.toString());
						if (!block) {
							// new connection needed?
							int p = url.getPort();
							if (p < 0) {
								p = PORT_HTTP;
							}
							if (tHost == null || !tHost.equals(url.getHost())
									|| tPort != p) {
								// create new connection
								Socket mSocket = this.remote;
								if (mSocket != null) {
									this.state = STATE_CLOSED_IN;
									synchronized (mSocket) {
										Log.d(TAG, "shutdown old remote");
										mSocket.shutdownInput();
										mSocket.shutdownOutput();
										mSocket.close();
										this.remote = null;
									}
									rThread.join();
								}
								tHost = url.getHost();
								tPort = url.getPort();
								if (tPort < 0) {
									tPort = PORT_HTTP;
								}
								Log.d(TAG, "new socket: " + url.toString());
								this.state = STATE_NORMAL;
								this.remote = new Socket();
								this.remote.connect(new InetSocketAddress(
										tHost, tPort));
								rInStream = this.remote.getInputStream();
								rOutStream = this.remote.getOutputStream();
								rThread = new Thread(new CopyStream(rInStream,
										lOutStream));
								rThread.start();
								if (url.getProtocol().startsWith("https")) {
									connectSSL = true;
									lWriter.write(HTTP_CONNECTED
											+ HTTP_RESPONSE);
									lWriter.flush();
									// copy local to remote by blocks
									Thread t2 = new Thread(new CopyStream(
											lInStream, rOutStream));

									t2.start();
									remoteWriter = null;
									break; // copy in separate thread. break
									// while here
								} else {
									remoteWriter = new BufferedWriter(
											new OutputStreamWriter(rOutStream),
											CopyStream.BUFFSIZE);
								}
							}
						}
					}
					// push data to remote if not blocked
					if (block) {
						lWriter.append(HTTP_BLOCK + HTTP_RESPONSE
								+ "BLOCKED by AdBlock!");
						lWriter.flush();
					} else if (this.remote != null && this.remote.isConnected()
							&& remoteWriter != null) {
						remoteWriter.append(buffer);
						remoteWriter.flush();
					}
				}
				if (rThread != null && rThread.isAlive()) {
					rThread.join();
				}
			} catch (InterruptedException e) {
				Log.e(TAG, null, e);
			} catch (IOException e) {
				Log.e(TAG, null, e);
				try {
					lWriter.append(HTTP_ERROR + " - " + e.toString()
							+ HTTP_RESPONSE + e.toString());
					lWriter.flush();
					lWriter.close();
					this.local.close();
				} catch (IOException e1) {
					Log.e(TAG, null, e1);
				}
			}
			Log.d(TAG, "close connection");
		}
	}

	/**
	 * Default Implementation.
	 * 
	 * @param intent
	 *            called Intent
	 * @return IBinder
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public final IBinder onBind(final Intent intent) {
		return null;
	}

	/**
	 * Called on start.
	 * 
	 * @param intent
	 *            Intent called
	 * @param startId
	 *            start ID
	 */
	@Override
	public final void onStart(final Intent intent, final int startId) {
		super.onStart(intent, startId);

		// Don't kill me!
		this.setForeground(true);

		SharedPreferences preferences = PreferenceManager
				.getDefaultSharedPreferences(this);
		int p = Integer.parseInt(preferences.getString(PREFS_PORT, "8080"));
		boolean portChanged = p != this.port;
		this.port = p;

		String f = preferences.getString(PREFS_FILTER, "");
		this.filter.clear();
		for (String s : f.split("\n")) {
			if (s.length() > 0) {
				this.filter.add(s);
			}
		}
		if (this.proxy == null) {
			// Toast.makeText(this, "starting proxy on port: " + this.port,
			// Toast.LENGTH_SHORT).show();
			this.proxy = new Thread(this);
			this.proxy.start();
		} else {
			Toast.makeText(this, "proxy running on port " + this.port,
					Toast.LENGTH_SHORT).show();
			if (portChanged) {
				this.proxy.interrupt();
				this.proxy = new Thread(this);
				this.proxy.start();
			}
		}
	}

	/**
	 * Called on destroy.
	 */
	@Override
	public final void onDestroy() {
		super.onDestroy();
		Toast.makeText(this, "stopping proxy..", Toast.LENGTH_LONG).show();
		this.stop = true;
	}

	/**
	 * Run by Thread.start().
	 */
	@Override
	public final void run() {
		try {
			int p = this.port;
			ServerSocket sock = new ServerSocket(p);
			Socket client;
			while (!this.stop && p == this.port) {
				if (p != this.port) {
					break;
				}
				client = sock.accept();
				if (client != null) {
					Log.d(TAG, "new client");
					Thread t = new Thread(new Connection(client));
					t.start();
				}
			}
			sock.close();
		} catch (IOException e) {
			Log.e(TAG, null, e);
		}
	}
}
