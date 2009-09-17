package de.ub0r.android.adBlock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;

public class CopyStream implements Runnable {
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
