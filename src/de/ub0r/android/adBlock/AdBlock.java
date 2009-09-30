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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

/**
 * Main Activity to control ad blocking Proxy.
 * 
 * @author Felix Bechstein
 */
public class AdBlock extends Activity implements OnClickListener,
		OnItemClickListener {

	/** Tag for output. */
	private final String TAG = "AdBlock";

	/** Preferences: Port. */
	static final String PREFS_PORT = "port";
	/** Preferences: Filter. */
	static final String PREFS_FILTER = "filter";
	/** Preferences: import url. */
	private static final String PREFS_IMPORT_URL = "importurl";

	/** Filename for export of filter. */
	private static final String FILENAME_EXPORT = "/sdcard/filter.txt";

	/** ItemDialog: edit. */
	private static final short ITEM_DIALOG_EDIT = 0;
	/** ItemDialog: delete. */
	private static final short ITEM_DIALOG_DELETE = 1;

	/** Dialog: about. */
	private static final int DIALOG_ABOUT = 0;
	/** Dialog: import. */
	private static final int DIALOG_IMPORT = 1;

	/** Prefs. */
	private SharedPreferences preferences;
	/** Prefs. import URL. */
	private String importUrl = null;

	/** The filter. */
	private ArrayList<String> filter = new ArrayList<String>();
	/** The ArrayAdapter. */
	private ArrayAdapter<String> adapter = null;

	/** Editmode? */
	private int itemToEdit = -1;

	private class Importer extends AsyncTask<String, Boolean, Boolean> {
		/** Error message. */
		private String message = "";

		@Override
		protected final Boolean doInBackground(final String... dummy) {
			try {
				HttpURLConnection c = (HttpURLConnection) (new URL(
						AdBlock.this.importUrl)).openConnection();
				int resp = c.getResponseCode();
				if (resp != 200) {
					return false;
				}
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(c.getInputStream()));
				AdBlock.this.filter.clear();
				while (true) {
					String s = reader.readLine();
					if (s == null) {
						break;
					} else if (s.length() > 0) {
						AdBlock.this.filter.add(s);
					}
				}
				reader.close();
				return true;
			} catch (MalformedURLException e) {
				Log.e(AdBlock.this.TAG, null, e);
				this.message = e.toString();
				return false;
			} catch (IOException e) {
				this.message = e.toString();
				Log.e(AdBlock.this.TAG, null, e);
				return false;
			}
		}

		@Override
		protected final void onPostExecute(final Boolean result) {
			if (result.booleanValue()) {
				Toast.makeText(AdBlock.this, "imported", Toast.LENGTH_LONG)
						.show();
				AdBlock.this.adapter.notifyDataSetChanged();
			} else {
				Toast.makeText(AdBlock.this, "failed: " + this.message,
						Toast.LENGTH_LONG).show();
			}
		}
	}

	/**
	 * Called when the activity is first created.
	 * 
	 * @param savedInstanceState
	 *            saved InstanceState
	 */
	@Override
	public final void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.setContentView(R.layout.main);

		this.preferences = PreferenceManager.getDefaultSharedPreferences(this);
		((EditText) this.findViewById(R.id.port)).setText(this.preferences
				.getString(PREFS_PORT, "8080"));
		String f = this.preferences.getString(PREFS_FILTER, "");
		for (String s : f.split("\n")) {
			if (s.length() > 0) {
				this.filter.add(s);
			}
		}
		this.importUrl = this.preferences.getString(PREFS_IMPORT_URL, "");

		((Button) this.findViewById(R.id.start_service))
				.setOnClickListener(this);
		((Button) this.findViewById(R.id.stop_service))
				.setOnClickListener(this);
		((Button) this.findViewById(R.id.filter_add_)).setOnClickListener(this);
		ListView lv = (ListView) this.findViewById(R.id.filter);
		this.adapter = new ArrayAdapter<String>(this,
				R.layout.simple_list_item_1, this.filter);
		lv.setAdapter(this.adapter);
		lv.setTextFilterEnabled(true);
		lv.setOnItemClickListener(this);
	}

	/** Save Preferences. */
	private void savePreferences() {
		SharedPreferences.Editor editor = this.preferences.edit();
		editor.putString(PREFS_PORT, ((EditText) this.findViewById(R.id.port))
				.getText().toString());
		StringBuilder sb = new StringBuilder();
		for (String s : this.filter) {
			if (s.indexOf("admob") < 0) { // won't block admob
				sb.append(s + "\n");
			}
		}
		editor.putString(PREFS_FILTER, sb.toString());
		editor.putString(PREFS_IMPORT_URL, this.importUrl);
		editor.commit();
	}

	/** Called on pause. */
	@Override
	public final void onPause() {
		super.onPause();
		this.savePreferences();
	}

	/**
	 * OnClickListener.
	 * 
	 * @param v
	 *            view
	 */
	@Override
	public final void onClick(final View v) {
		switch (v.getId()) {
		case R.id.start_service:
			this.savePreferences();
			this.startService(new Intent(this, Proxy.class));
			break;
		case R.id.stop_service:
			this.stopService(new Intent(this, Proxy.class));
		case R.id.filter_add_:
			EditText et = (EditText) this.findViewById(R.id.filter_add);
			String f = et.getText().toString();
			if (f.length() > 0) {
				if (this.itemToEdit >= 0) {
					this.filter.remove(this.itemToEdit);
					this.itemToEdit = -1;
				}
				this.filter.add(f);
				et.setText("");
				this.adapter.notifyDataSetChanged();
			}
			break;
		case R.id.cancel:
			this.dismissDialog(DIALOG_IMPORT);
			break;
		case R.id.ok:
			this.dismissDialog(DIALOG_IMPORT);
			this.importUrl = ((EditText) v.getRootView().findViewById(
					R.id.import_url)).getText().toString();
			new Importer().execute((String[]) null);
			break;
		default:
			break;
		}
	}

	/**
	 * Create menu.
	 * 
	 * @param menu
	 *            menu to inflate
	 * @return ok/fail?
	 */
	@Override
	public final boolean onCreateOptionsMenu(final Menu menu) {
		MenuInflater inflater = this.getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	/**
	 * Handles item selections.
	 * 
	 * @param item
	 *            menu item
	 * @return done?
	 */
	public final boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
		case R.id.item_about: // start about dialog
			this.showDialog(DIALOG_ABOUT);
			return true;
		case R.id.item_import:
			this.showDialog(DIALOG_IMPORT);
			return true;
		case R.id.item_export:
			try {
				// OutputStream os = this.openFileOutput(FILENAME_EXPORT,
				// MODE_WORLD_READABLE);
				File file = new File(FILENAME_EXPORT);
				if (!file.createNewFile()) {
					file.delete();
					file.createNewFile();
				}
				OutputStream os = new FileOutputStream(file);
				BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
						os));
				for (String s : this.filter) {
					if (s.indexOf("admob") < 0) { // won't block admob
						bw.append(s + "\n");
					}
				}
				bw.close();
				os.close();
				Toast.makeText(this, "exported to " + FILENAME_EXPORT,
						Toast.LENGTH_LONG).show();
			} catch (IOException e) {
				Log.e(this.TAG, null, e);
				Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
			}
			return true;
		default:
			return false;
		}
	}

	/**
	 * Called to create dialog.
	 * 
	 * @param id
	 *            Dialog id
	 * @return dialog
	 */
	@Override
	protected final Dialog onCreateDialog(final int id) {
		Dialog myDialog;
		switch (id) {
		case DIALOG_ABOUT:
			myDialog = new Dialog(this);
			myDialog.setContentView(R.layout.about);
			myDialog.setTitle(this.getResources().getString(R.string.about_)
					+ " v"
					+ this.getResources().getString(R.string.app_version));
			// ((Button) myDialog.findViewById(R.id.btn_donate))
			// .setOnClickListener(this);
			break;
		case DIALOG_IMPORT:
			myDialog = new Dialog(this);
			myDialog.setContentView(R.layout.import_url);
			myDialog.setTitle(this.getResources().getString(
					R.string.import_url_));
			((Button) myDialog.findViewById(R.id.ok)).setOnClickListener(this);
			((Button) myDialog.findViewById(R.id.cancel))
					.setOnClickListener(this);
			break;
		default:
			myDialog = null;
		}
		return myDialog;
	}

	/**
	 * Provides an opportunity to prepare a managed dialog before it is being
	 * shown.
	 * 
	 * @param id
	 *            The id of the managed dialog.
	 * @param dialog
	 *            The dialog.
	 */
	@Override
	protected final void onPrepareDialog(final int id, final Dialog dialog) {
		super.onPrepareDialog(id, dialog);
		switch (id) {
		case DIALOG_IMPORT:
			((EditText) dialog.findViewById(R.id.import_url))
					.setText(this.importUrl);
			break;
		default:
			break;
		}
	}

	/**
	 * Handle clicked ListItem.
	 * 
	 * @param parent
	 *            parent AdapterView
	 * @param v
	 *            View
	 * @param position
	 *            Position
	 * @param id
	 *            id
	 */
	@Override
	public final void onItemClick(final AdapterView<?> parent, final View v,
			final int position, final long id) {

		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setItems(
				this.getResources().getStringArray(R.array.itemDialog),
				new DialogInterface.OnClickListener() {
					public void onClick(final DialogInterface dialog,
							final int item) {
						switch (item) {
						case ITEM_DIALOG_EDIT:
							AdBlock.this.itemToEdit = position;
							((EditText) AdBlock.this
									.findViewById(R.id.filter_add))
									.setText(AdBlock.this.adapter
											.getItem(position));
							break;
						case ITEM_DIALOG_DELETE:
							AdBlock.this.filter.remove(position);
							AdBlock.this.adapter.notifyDataSetChanged();
							break;
						default:
							break;
						}
					}
				});
		AlertDialog alert = builder.create();
		alert.show();
	}
}
