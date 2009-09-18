package de.ub0r.android.adBlock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

public class AdBlock extends Activity {
	/** Called when the activity is first created. */
	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.setContentView(R.layout.main);

		((Button) this.findViewById(R.id.start_service))
				.setOnClickListener(this.runStart);
		((Button) this.findViewById(R.id.stop_service))
				.setOnClickListener(this.runStop);
	}

	/** OnClickListener. */
	private OnClickListener runStart = new OnClickListener() {
		public void onClick(final View v) {
			AdBlock.this.startService(new Intent(AdBlock.this, Proxy.class));
		}
	};

	/** OnClickListener. */
	private OnClickListener runStop = new OnClickListener() {
		public void onClick(final View v) {
			AdBlock.this.stopService(new Intent(AdBlock.this, Proxy.class));
		}
	};
}