package de.ub0r.android.adBlock;

import java.util.ArrayList;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.AdapterView.OnItemClickListener;

/**
 * Main Activity to controle ad blocking proxy.
 * 
 * @author flx
 */
public class AdBlock extends Activity implements OnClickListener,
		OnItemClickListener {

	/** ItemDialog: edit. */
	private static final short ITEM_DIALOG_EDIT = 0;
	/** ItemDialog: delete. */
	private static final short ITEM_DIALOG_DELETE = 1;

	/** The filter. */
	private ArrayList<String> filter = new ArrayList<String>();
	/** The ArrayAdapter. */
	private ArrayAdapter<String> adapter = null;

	/** Editmode? */
	private int itemToEdit = -1;

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

		((Button) this.findViewById(R.id.start_service))
				.setOnClickListener(this);
		((Button) this.findViewById(R.id.stop_service))
				.setOnClickListener(this);
		((Button) this.findViewById(R.id.filter_add_)).setOnClickListener(this);
		ListView lv = (ListView) this.findViewById(R.id.filter);
		this.adapter = new ArrayAdapter<String>(this,
				android.R.layout.simple_list_item_1, this.filter);
		lv.setAdapter(this.adapter);
		lv.setTextFilterEnabled(true);
		lv.setOnItemClickListener(this);
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
		// builder.setTitle("Pick a color");
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
