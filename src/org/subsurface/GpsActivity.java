/* Subsurface for Android
 * Copyright (C) 2012  Aurelien PRALONG
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.subsurface;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.subsurface.dao.DbAdapter;
import org.subsurface.dao.DiveLocationLogDao;
import org.subsurface.model.DiveLocationLog;
import org.subsurface.ws.WsClient;
import org.subsurface.ws.WsClientStub;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Activity for location operations.
 * @author Aurelien PRALONG
 *
 */
public class GpsActivity extends Activity {

	private static final String TAG = "GpsActivity";

	private final WsClient wsClient = new WsClientStub();
	private LocationManager locationManager;
	private EditText locationName;
	private DiveLocationLogDao locationDao;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gps);

        locationName = (EditText) findViewById(R.id.locationName);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locationDao = new DbAdapter(this).getDiveLocationLogDao();
        locationDao.open();
    }

    @Override
    protected void onDestroy() {
    	super.onDestroy();
    	locationDao.close();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_gps, menu);
        return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
    	if (item.getItemId() == R.id.menu_settings) { // Settings
    		startActivity(new Intent(this, Preferences.class));
    		return true;
    	} else if (item.getItemId() == R.id.menu_locate) { // Locate has been clicked
    		final DiveLocationLog locationLog = new DiveLocationLog();
    		locationLog.setName(locationName.getText().toString());
    		final AtomicBoolean cancel = new AtomicBoolean(false);
    		final ProgressDialog waitDialog = ProgressDialog.show(
    				GpsActivity.this,
    				"", getString(R.string.wait_dialog),
    				true, true, new DialogInterface.OnCancelListener() {
						@Override
						public void onCancel(DialogInterface dialog) {
							cancel.set(true);
							Log.d(TAG, "Location cancelled");
						}
					});
			locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
				
				@Override
				public void onStatusChanged(String provider, int status, Bundle extras) {
					if (!cancel.get()) {
						waitDialog.dismiss();
					}
				}

				@Override
				public void onProviderEnabled(String provider) {
					if (!cancel.get()) {
						waitDialog.dismiss();
					}
				}

				@Override
				public void onProviderDisabled(String provider) {
					if (!cancel.get()) {
						waitDialog.dismiss();
						Toast.makeText(GpsActivity.this, R.string.error_location, Toast.LENGTH_SHORT).show();
					}
				}
				
				@Override
				public void onLocationChanged(final Location location) {
					if (!cancel.get()) {
						waitDialog.dismiss();
						Toast.makeText(GpsActivity.this, getString(R.string.confirmation_location_picked, locationLog.getName()), Toast.LENGTH_SHORT).show();
						new Thread(new Runnable() {
							public void run() {
								locationLog.setLocation(location);
								locationLog.setTimestamp(System.currentTimeMillis());
								SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(GpsActivity.this);
								String destUrl = prefs.getString("destination_url", null);
								String userId = prefs.getString("user_id", null);
								if (destUrl != null || userId != null) {
									try {
										wsClient.postDive(locationLog, destUrl, userId);
										locationDao.deleteDiveLocationLog(locationLog);
									} catch (Exception e) {
										Log.d(TAG, "Could not send dive " + locationLog.getName(), e);
										locationDao.save(locationLog);
										runOnUiThread(new Runnable() {
											public void run() {
												Toast.makeText(GpsActivity.this, R.string.error_send, Toast.LENGTH_SHORT).show();
											}
										});
									}
								}
							}
						}).start();
					}
				}
			}, null);
    	} else if (item.getItemId() == R.id.menu_send) { // Send has been clicked
    		// Should be get in a thread, but ProgressDialog does not allow post-show modifications...
    		final List<DiveLocationLog> locations = locationDao.getAllDiveLocationLogs();
    		final ProgressDialog dialog = new ProgressDialog(this);
    		final AtomicBoolean cancel = new AtomicBoolean(false);
    		dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			dialog.setMax(locations.size());
			dialog.setProgress(0);
    		dialog.setMessage(getString(R.string.dialog_wait_send));
    		dialog.show();
    		dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
				@Override
				public void onCancel(DialogInterface dialog) {
					cancel.set(true);
				}
			});
    		final Handler handler = new Handler() {
    			@Override
    			public void handleMessage(Message msg) {
    				int total = msg.arg1;
    				dialog.setProgress(total);
    				if (total >= locations.size()) { // OK, close dialog
    					dialog.dismiss();
    				}
    			}
    		};
			new Thread(new Runnable() {
				public void run() {
					int success = 0;
					boolean urlFailure = false;
					// Send locations
					for (int i = 0; i < locations.size() && !cancel.get(); ++i) {
						DiveLocationLog log = locations.get(i);
						SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(GpsActivity.this);
						String destUrl = prefs.getString("destination_url", null);
						String userId = prefs.getString("user_id", null);
						if (destUrl != null || userId != null) { // Could not build URL, show error
							runOnUiThread(new Runnable() {
								public void run() {
									Toast.makeText(GpsActivity.this, R.string.error_no_settings, Toast.LENGTH_SHORT).show();
								}
							});
							urlFailure = true;
							break;
						} else {
							try {
								wsClient.postDive(log, destUrl, userId);
								locationDao.deleteDiveLocationLog(log);
								++success;
							} catch (Exception e) {
								Log.d(TAG, "Could not send dive " + log.getName(), e);
							}
						}
						// Update progress
						Message msg = handler.obtainMessage();
						msg.arg1 = i + 1;
						handler.sendMessage(msg);
					}

					// 100 % 
					Message msg = handler.obtainMessage();
					msg.arg1 = locations.size();
					handler.sendMessage(msg);

					if (!urlFailure) {
						final int successCount = success;
						final int totalCount = locations.size();
						runOnUiThread(new Runnable() {
							public void run() {
								Toast.makeText(GpsActivity.this, getString(R.string.confirmation_location_sent, successCount, totalCount), Toast.LENGTH_SHORT).show();
							}
						});
					}
				}
			}).start();
    	}
    	return super.onMenuItemSelected(featureId, item);
    }
}
