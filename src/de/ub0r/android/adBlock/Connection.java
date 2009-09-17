package de.ub0r.android.adBlock;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;

import android.content.Context;
import android.widget.Toast;

public class Connection implements Runnable {

	private final Socket sock;
	private final Context cont;

	public Connection(final Socket socket, Context context) {
		this.sock = socket;
		this.cont = context;
	}

	@Override
	public void run() {
		try {
			BufferedReader localReader = new BufferedReader(
					new InputStreamReader(sock.getInputStream()));
			BufferedWriter localWriter = new BufferedWriter(
					new OutputStreamWriter(sock.getOutputStream()));
			while (true) {
				System.out.println(localReader.readLine());
			}
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(cont, e.toString(), Toast.LENGTH_LONG).show();
		}
	}

}
