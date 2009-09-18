package de.ub0r.android.adBlock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

import android.content.Context;
import android.widget.Toast;

public class Connection implements Runnable {

	private final Socket local;
	private Socket remote;
	private final Context cont;

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
					remoteReader = new BufferedReader(new InputStreamReader(
							this.remote.getInputStream()));
					remoteWriter = new BufferedWriter(new OutputStreamWriter(
							this.remote.getOutputStream()));
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
			Toast.makeText(this.cont, e.toString(), Toast.LENGTH_LONG).show();
		}
	}
}
