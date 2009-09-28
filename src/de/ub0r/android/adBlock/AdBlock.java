package de.ub0r.android.adBlock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

/**
 * Main Activity to controle ad blocking proxy.
 * 
 * @author flx
 */
public class AdBlock extends Activity implements OnClickListener {
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
	}

	/**
	 * OnClickListener.
	 * 
	 * @param v
	 *            view
	 */
	public final void onClick(final View v) {
		switch (v.getId()) {
		case R.id.start_service:
			this.startService(new Intent(this, Proxy.class));
			break;
		case R.id.stop_service:
			this.stopService(new Intent(this, Proxy.class));
		default:
			break;
		}
	}
}
