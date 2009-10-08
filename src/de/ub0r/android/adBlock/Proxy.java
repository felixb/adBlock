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

		/** Local Socket. */
		private final Socket local;
		/** Remote Socket. */
		private Socket remote;

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
			/** Object to notify with at EOF. */
			private final Object sync;
			/** Size of buffer. */
			private static final short BUFFSIZE = 256;

			/**
			 * Constructor.
			 * 
			 * @param r
			 *            reader
			 * @param w
			 *            writer
			 * @param s
			 *            object to sync with
			 */
			public CopyStream(final InputStream r, final OutputStream w,
					final Object s) {
				this.reader = r;
				this.writer = w;
				this.sync = s;
			}

			/**
			 * Run by Thread.start().
			 */
			@Override
			public void run() {
				try {
					byte[] buf = new byte[BUFFSIZE];
					int read = -1;
					while (true) {
						read = this.reader.read(buf);
						if (read < 0) {
							break;
						}
						this.writer.write(buf, 0, read);
						this.writer.flush();
					}
					synchronized (this.sync) {
						this.sync.notify();
					}
				} catch (IOException e) {
					Log.e(TAG, null, e);
				}
			}
		}

		/**
		 * Constructor.
		 * 
		 * @param socket
		 *            local Socket
		 * @param context
		 *            global Context
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
		 * Run by Thread.start().
		 */
		@Override
		public void run() {
			try {
				InputStream localInputStream = this.local.getInputStream();
				OutputStream localOutputStream = this.local.getOutputStream();
				BufferedReader localReader = new BufferedReader(
						new InputStreamReader(localInputStream),
						CopyStream.BUFFSIZE);
				BufferedWriter localWriter = new BufferedWriter(
						new OutputStreamWriter(localOutputStream),
						CopyStream.BUFFSIZE);
				try {
					InputStream remoteInputStream = null;
					OutputStream remoteOutputStream = null;
					StringBuilder buffer = new StringBuilder();
					String s;
					boolean firstLine = true;
					boolean block = false;
					boolean uncompleteURL = true;
					boolean connectHTTPS = false;
					String url = null;
					String targetHost = null;
					int targetPort = -1;
					while (this.remote == null && !block) {
						s = localReader.readLine();
						buffer.append(s + "\n");
						Log.v(TAG, s);
						if (firstLine) {
							url = s.split(" ")[1];
							if (s.startsWith("CONNECT ")) {
								targetPort = 443;
								targetHost = url;
								int i = targetHost.indexOf(':');
								if (i > 0) {
									targetPort = Integer.parseInt(targetHost
											.substring(i + 1));
									targetHost = targetHost.substring(0, i);
								}
								connectHTTPS = true;
							} else if (url.startsWith("http:")) {
								uncompleteURL = false;
								block = this.checkURL(url);
							} else {
								uncompleteURL = true;
							}
							firstLine = false;
						}
						if (!block && s.startsWith("Host:")) {
							// init remote socket
							targetPort = 80;
							targetHost = s.substring(6).trim();
							int i = targetHost.indexOf(':');
							if (i > 0) {
								targetPort = Integer.parseInt(targetHost
										.substring(i + 1));
								targetHost = targetHost.substring(0, i);
							}
							if (uncompleteURL) {
								url = targetHost + url;
								block = this.checkURL(url);
							}
							if (block) {
								break;
							}
						} else if (!block && s.length() == 0) { // end of header
							if (url.startsWith("http")) {
								URL u = new URL(url);
								targetHost = u.getHost();
								targetPort = u.getPort();
								if (targetPort < 0) {
									targetPort = 80;
								}
							} else {
								localWriter.append(HTTP_ERROR
										+ " - PROTOCOL ERROR" + HTTP_RESPONSE
										+ "PROTOCOL ERROR");
								localWriter.flush();
								localWriter.close();
								this.local.close();
								break;
							}
						}
						if (targetHost != null && targetPort > 0) {
							Log.v(TAG, "connect to " + targetHost + " "
									+ targetPort);
							this.remote = new Socket();
							this.remote.connect(new InetSocketAddress(
									targetHost, targetPort));
							remoteInputStream = this.remote.getInputStream();
							remoteOutputStream = this.remote.getOutputStream();
							if (connectHTTPS) {
								localWriter.write(HTTP_CONNECTED
										+ HTTP_RESPONSE);
							} else {
								BufferedWriter remoteWriter = new BufferedWriter(
										new OutputStreamWriter(
												remoteOutputStream),
										CopyStream.BUFFSIZE);
								while (localReader.ready()) {
									buffer
											.append(localReader.readLine()
													+ "\n");
								}
								remoteWriter.append(buffer);
								remoteWriter.flush();
							}
							buffer = null;
							// remoteWriter.close(); // writer.close() will
							// close underlying socket!
						}
					}
					if (this.remote != null && this.remote.isConnected()) {
						Object sync = new Object();
						localWriter.flush();
						localOutputStream.flush();
						remoteOutputStream.flush();
						Thread t1 = new Thread(new CopyStream(
								remoteInputStream, localOutputStream, sync));
						Thread t2 = new Thread(new CopyStream(localInputStream,
								remoteOutputStream, sync));
						try {
							synchronized (sync) {
								t1.start();
								t2.start();
								sync.wait();
							}
						} catch (InterruptedException e) {
							Log.e(TAG, null, e);
						}
						this.local.shutdownInput();
						this.remote.shutdownInput();
						this.local.shutdownOutput();
						this.remote.shutdownOutput();
						this.remote.close();
						this.local.close();
						t1.join();
						t2.join();
					} else if (block) {
						while (localReader.ready()) {
							localReader.readLine();
						}
						localWriter.append(HTTP_BLOCK + HTTP_RESPONSE
								+ "BLOCKED by AdBlock!");
						localWriter.flush();
						localWriter.close();
						this.local.close();
					}
				} catch (InterruptedException e) {
					// do nothing
				} catch (NullPointerException e) {
					// do nothing
				} catch (Exception e) {
					Log.e(TAG, null, e);
					localWriter.append(HTTP_ERROR + " - " + e.toString()
							+ HTTP_RESPONSE + e.toString());
					localWriter.flush();
					localWriter.close();
					this.local.close();
				}
			} catch (IOException e1) {
				Log.e(TAG, null, e1);
			}
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
		int p = Integer.parseInt(preferences.getString(PREFS_PORT,
				"8080"));
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
