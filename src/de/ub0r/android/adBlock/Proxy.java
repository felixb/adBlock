/**
 * 
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

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.widget.Toast;

/**
 * @author flx
 */
public class Proxy extends Service implements Runnable {

	private Thread proxy = null;
	private int port = 8088;
	private boolean stop = false;

	private class Connection implements Runnable {

		private final Socket local;
		private Socket remote;
		private final Context cont;

		private class CopyStream implements Runnable {
			private final BufferedReader reader;
			private final BufferedWriter writer;
			private final Socket socket;

			public CopyStream(final BufferedReader r, final BufferedWriter w,
					final Socket s) {
				this.reader = r;
				this.writer = w;
				this.socket = s;
			}

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
					this.socket.close();
				} catch (IOException e) {
					// handle exception
				}
			}
		}

		public Connection(final Socket socket, final Context context) {
			this.local = socket;
			this.cont = context;
		}

		@Override
		public void run() {
			try {
				BufferedReader localReader = new BufferedReader(
						new InputStreamReader(this.local.getInputStream()));
				BufferedWriter localWriter = new BufferedWriter(
						new OutputStreamWriter(this.local.getOutputStream()));
				BufferedReader remoteReader = null;
				BufferedWriter remoteWriter = null;
				StringBuilder buffer = new StringBuilder();
				String s;
				while (this.remote == null) {
					s = localReader.readLine();
					buffer.append(s + "\n");
					System.out.println(s);
					if (s.startsWith("Host:")) {
						// init remote socket
						int targetPort = 80;
						String targetHost = s.substring(6).trim();
						int i = targetHost.indexOf(':');
						if (i > 0) {
							targetPort = Integer.parseInt(targetHost
									.substring(i + 1));
							targetHost = targetHost.substring(0, i);
						}
						System.out.println("connect to " + targetHost + " "
								+ targetPort);
						this.remote = new Socket();
						this.remote.connect(new InetSocketAddress(targetHost,
								targetPort));
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
					Thread t1 = new Thread(new CopyStream(remoteReader,
							localWriter, this.remote));
					t1.start();
					Thread t2 = new Thread(new CopyStream(localReader,
							remoteWriter, this.remote));
					t2.start();
					t1.join();
					t2.join();
					System.out.println("close local");
					this.local.close();
				}
			} catch (InterruptedException e) {
				// do nothing
			} catch (NullPointerException e) {
				// do nothing
			} catch (Exception e) {
				e.printStackTrace();
				Toast.makeText(this.cont, e.toString(), Toast.LENGTH_LONG)
						.show();
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Service#onBind(android.content.Intent)
	 */
	@Override
	public IBinder onBind(final Intent intent) {
		return null;
	}

	@Override
	public void onStart(final Intent intent, final int startId) {
		super.onStart(intent, startId);
		if (this.proxy == null) {
			Toast.makeText(this, "starting proxy..", Toast.LENGTH_LONG).show();
			this.proxy = new Thread(this);
			this.proxy.start();
		} else {
			Toast.makeText(this, "proxy running", Toast.LENGTH_LONG).show();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Toast.makeText(this, "stopping proxy..", Toast.LENGTH_LONG).show();
		this.stop = true;
	}

	@Override
	public void run() {
		try {
			ServerSocket sock = new ServerSocket(this.port);
			Socket client;
			while (!this.stop) {
				client = sock.accept();
				if (client != null) {
					Thread t = new Thread(new Connection(client, this));
					t.start();
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
		}
	}
}
