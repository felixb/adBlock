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
import java.io.InputStreamReader;
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
import android.widget.Toast;

/**
 * This ad blocking Proxy Service will work as an ordinary HTTP proxy. Set APN's
 * proxy preferences to proxy's connection parameters.
 * 
 * @author Felix Bechstein
 */
public class Proxy extends Service implements Runnable {

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
			private final BufferedReader reader;
			/** Writer. */
			private final BufferedWriter writer;
			/** Object to notify with at EOF. */
			private final Object sync;

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
			public CopyStream(final BufferedReader r, final BufferedWriter w,
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
					String s;
					do {
						s = this.reader.readLine();
						if (s == null) {
							break;
						}
						this.writer.append(s + "\n");
						this.writer.flush();
					} while (true);
					System.out.println("close remote");
					synchronized (this.sync) {
						this.sync.notify();
					}
				} catch (IOException e) {
					// handle exception
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
			System.out.println("check url: " + url);
			for (String f : Proxy.this.filter) {
				System.out.println("check filter: " + f);
				if (url.indexOf(f) >= 0) {
					System.out.println("block: " + url);
					System.out.println("match: " + f);
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
				BufferedReader localReader = new BufferedReader(
						new InputStreamReader(this.local.getInputStream()));
				BufferedWriter localWriter = new BufferedWriter(
						new OutputStreamWriter(this.local.getOutputStream()));
				try {
					BufferedReader remoteReader = null;
					BufferedWriter remoteWriter = null;
					StringBuilder buffer = new StringBuilder();
					String s;
					boolean firstLine = true;
					boolean block = false;
					boolean uncompleteURL = true;
					String url = null;
					String targetHost = null;
					int targetPort = -1;
					while (this.remote == null && !block) {
						s = localReader.readLine();
						buffer.append(s + "\n");
						System.out.println(s);
						if (firstLine) {
							url = s.split(" ")[1];
							if (url.startsWith("http:")) {
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
							System.out.println("connect to " + targetHost + " "
									+ targetPort);
							this.remote = new Socket();
							this.remote.connect(new InetSocketAddress(
									targetHost, targetPort));
							remoteReader = new BufferedReader(
									new InputStreamReader(this.remote
											.getInputStream()));
							remoteWriter = new BufferedWriter(
									new OutputStreamWriter(this.remote
											.getOutputStream()));
							remoteWriter.append(buffer);
							remoteWriter.flush();
							buffer = null;
						}
					}
					if (this.remote != null && this.remote.isConnected()) {
						Object sync = new Object();

						Thread t1 = new Thread(new CopyStream(remoteReader,
								localWriter, sync));
						Thread t2 = new Thread(new CopyStream(localReader,
								remoteWriter, sync));
						try {
							synchronized (sync) {
								t1.start();
								t2.start();
								sync.wait();
							}
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
						t1.join();
						t2.join();
						this.local.close();
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
					e.printStackTrace();
					localWriter.append(HTTP_ERROR + " - " + e.toString()
							+ HTTP_RESPONSE + e.toString());
					localWriter.flush();
					localWriter.close();
					this.local.close();
				}
			} catch (IOException e1) {
				// nothing
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
		int p = Integer.parseInt(preferences.getString(AdBlock.PREFS_PORT,
				"8080"));
		boolean portChanged = p != this.port;
		this.port = p;

		String f = preferences.getString(AdBlock.PREFS_FILTER, "");
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
			e.printStackTrace();
		}
	}
}
