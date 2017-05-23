package com.telenor.possumlib.detectors;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.google.common.eventbus.EventBus;
import com.telenor.possumlib.abstractdetectors.AbstractEventDrivenDetector;
import com.telenor.possumlib.changeevents.BasicChangeEvent;
import com.telenor.possumlib.changeevents.LocationChangeEvent;
import com.telenor.possumlib.constants.DetectorType;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/***
 * Uses gps with network to retrieve a position regularly
 */
public class LocationDetector extends AbstractEventDrivenDetector implements LocationListener {
    private static final String SINGLE_POSITION_SCAN = "SINGLE_POSITION_SCAN";
    private LocationManager locationManager;
    private boolean gpsAvailable;
    private boolean networkAvailable;
    private BroadcastReceiver providerChangedReceiver;
    private boolean isRegistered;
    private float maxSpeed;
    private List<String> providers;
    private Timer timer;

    public LocationDetector(Context context, String identification, String secretKeyHash, @NonNull EventBus eventBus) {
        super(context, identification, secretKeyHash, eventBus);
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            Log.d(tag, "No positioning available");
            return;
        }
        providers = locationManager.getAllProviders();
        networkAvailable = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        gpsAvailable = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        providerChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    try {
                        int locationMode = Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE);
                        switch (locationMode) {
                            case Settings.Secure.LOCATION_MODE_HIGH_ACCURACY:
                                // gps, bluetooth, wifi, mobile networks
                                gpsAvailable = true;
                                networkAvailable = true;
                                break;
                            case Settings.Secure.LOCATION_MODE_OFF:
                                // none
                                gpsAvailable = false;
                                networkAvailable = false;
                                break;
                            case Settings.Secure.LOCATION_MODE_SENSORS_ONLY:
                                // gps
                                gpsAvailable = true;
                                networkAvailable = false;
                                break;
                            case Settings.Secure.LOCATION_MODE_BATTERY_SAVING:
                                // Uses wifi, bluetooth & mobile networks
                                gpsAvailable = false;
                                networkAvailable = true;
                                break;
                            default:
                                Log.d(tag, "Unhandled mode:" + locationMode);
                        }
                        sensorStatusChanged();
                    } catch (Settings.SettingNotFoundException e) {
                        Log.e(tag, "Settings not found:", e);
                    }
                } else {
                    // TODO: Confirm this is correct way to find provider below api 19
                    gpsAvailable = isProviderAvailable(LocationManager.GPS_PROVIDER);
                    networkAvailable = isProviderAvailable(LocationManager.NETWORK_PROVIDER);
                }
            }
        };
        context().registerReceiver(providerChangedReceiver, new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION));
        isRegistered = true;
        maxSpeed = 0.0f;
    }

    /**
     * Confirms the right to use the fine location permission and that at least one of the providers is enabled/available
     *
     * @return whether this detector is enabled
     */
    @Override
    public boolean isEnabled() {
        return locationManager != null && (providers != null && !providers.isEmpty());
    }

    /**
     * Confirms the existence of a given provider
     * @param provider the provider to check for
     * @return true if provider is found, false if not
     */
    public boolean isProviderEnabled(String provider) {
        return locationManager != null && locationManager.getAllProviders().contains(provider);
    }

    /**
     * Confirms whether use has allowed to use a specific provider
     * @param provider the provider to check for
     * @return true if provider is permitted and available, false if not
     */
    public boolean isProviderAvailable(String provider) {
        boolean permission = isPermitted();
        switch (provider) {
            case LocationManager.GPS_PROVIDER:
                return permission && gpsAvailable;
            case LocationManager.NETWORK_PROVIDER:
                return permission && networkAvailable;
            default:
                Log.d(tag, "Unknown provider:" + provider);
                return false;
        }
    }

    @Override
    public void terminate() {
        if (isRegistered) {
            context().unregisterReceiver(providerChangedReceiver);
            isRegistered = false;
        }
        cancelScan();
        super.terminate();
    }

    /**
     * Confirms whether detector is permitted to be used
     * @return true if allowed, else false
     */
    public boolean isPermitted() {
        return ContextCompat.checkSelfPermission(context(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void cancelScan() {
        if (isPermitted()) {
            locationManager.removeUpdates(this);
        }
    }
    @SuppressWarnings("MissingPermission")
    private void performScan() {
        if (isEnabled()) {
            boolean scanStarted = false;
            if (isProviderAvailable(LocationManager.GPS_PROVIDER) && isPermitted()) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, this, Looper.getMainLooper());
                scanStarted = true;
            }
            if (isProviderAvailable(LocationManager.NETWORK_PROVIDER) && isPermitted()) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, this, Looper.getMainLooper());
                scanStarted = true;
            }
            if (scanStarted) {
                if (timer != null) {
                    timer.cancel();
                    timer.purge();
                }
                timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        cancelScan();
                    }
                }, scanTimeout());
            }
        } else {
            Log.d(tag, "Unable to scan for position:" + isAvailable() + "/" + isEnabled());
        }
    }

    private long scanTimeout() {
        return 60000; // 1,0 minute scan timeout
    }

    @Override
    public boolean startListening() {
        boolean listen = super.startListening();
        if (listen) {
            performScan();
        }
        return listen;
    }

    @Override
    public void eventReceived(BasicChangeEvent object) {
        if (object instanceof LocationChangeEvent) {
            LocationChangeEvent event = (LocationChangeEvent)object;
            switch (event.eventType()) {
                case SINGLE_POSITION_SCAN:
                    performScan();
                    break;
                default:
                    Log.d(tag, "Unknown event in location detector:" + event.eventType());
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        String output = location.getTime() + " " + location.getLatitude() + " " + location.getLongitude() + " " + location.getAltitude() + " " + location.getAccuracy() + " " + location.getProvider();
        sessionValues.add(output);
        float speed = location.getSpeed();
        if (speed > maxSpeed) {
            maxSpeed = speed;
        }
        storeData();
    }

    @Override
    protected boolean storeWithInterval() {
        return false;
    }

    @Override
    public boolean isValidSet() {
        return true;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        switch (status) {
            case LocationProvider.AVAILABLE:
                switch (provider) {
                    case LocationManager.GPS_PROVIDER:
                        gpsAvailable = true;
                        break;
                    case LocationManager.NETWORK_PROVIDER:
                        networkAvailable = true;
                        break;
                    default:
                }
                break;
            case LocationProvider.OUT_OF_SERVICE:
            case LocationProvider.TEMPORARILY_UNAVAILABLE:
                switch (provider) {
                    case LocationManager.GPS_PROVIDER:
                        gpsAvailable = false;
                        break;
                    case LocationManager.NETWORK_PROVIDER:
                        networkAvailable = false;
                        break;
                    default:
                }
        }
        sensorStatusChanged();
    }

    @Override
    public void onProviderEnabled(String provider) {
        switch (provider) {
            case LocationManager.GPS_PROVIDER:
                gpsAvailable = true;
                break;
            case LocationManager.NETWORK_PROVIDER:
                networkAvailable = true;
                break;
            default:
        }
        sensorStatusChanged();
    }

    @Override
    public void onProviderDisabled(String provider) {
        switch (provider) {
            case LocationManager.GPS_PROVIDER:
                gpsAvailable = false;
                break;
            case LocationManager.NETWORK_PROVIDER:
                networkAvailable = false;
                break;
            default:
        }
        sensorStatusChanged();
    }

    @Override
    public int detectorType() {
        return DetectorType.Position;
    }

    @Override
    public String detectorName() {
        return "Position";
    }

    @Override
    public boolean isAvailable() {
        return (gpsAvailable || networkAvailable) && isPermitted();
    }
}