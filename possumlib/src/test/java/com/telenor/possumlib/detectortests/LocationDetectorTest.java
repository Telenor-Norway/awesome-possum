package com.telenor.possumlib.detectortests;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Process;

import com.google.common.eventbus.EventBus;
import com.telenor.possumlib.PossumTestRunner;
import com.telenor.possumlib.constants.DetectorType;
import com.telenor.possumlib.interfaces.ISensorStatusUpdate;
import com.telenor.possumlib.detectors.LocationDetector;

import junit.framework.Assert;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@RunWith(PossumTestRunner.class)
public class LocationDetectorTest {
    private LocationDetector locationDetector;
    private LocationManager mockedLocationManager;
    private Context mockedContext;
    private int changedStatus;
    private EventBus eventBus;
    @Before
    public void setUp() throws Exception {
        mockedContext = Mockito.mock(Context.class);
        changedStatus = 0;
        eventBus = new EventBus();
        // TODO: Use ShadowLocationManager instead of mock?
        mockedLocationManager = Mockito.mock(LocationManager.class);
        when(mockedContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mockedLocationManager);
        when(mockedLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)).thenReturn(true);
        when(mockedLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)).thenReturn(true);
        List<String> allProviders = new ArrayList<>();
        allProviders.add(LocationManager.GPS_PROVIDER);
        allProviders.add(LocationManager.NETWORK_PROVIDER);
        when(mockedLocationManager.getAllProviders()).thenReturn(allProviders);
        locationDetector = new LocationDetector(mockedContext, "fakeUnique", "fakeId", eventBus);
    }

    @After
    public void tearDown() throws Exception {
        locationDetector = null;
    }

    @Test
    public void testEnabledWithBoth() throws Exception {
        Assert.assertTrue(locationDetector.isEnabled());
    }

    @Test
    public void testNotEnabled() throws Exception {
        mockedContext = Mockito.mock(Context.class);
        mockedLocationManager = Mockito.mock(LocationManager.class);
        when(mockedContext.getSystemService(Context.LOCATION_SERVICE)).thenReturn(mockedLocationManager);
        when(mockedLocationManager.getAllProviders()).thenReturn(Collections.<String>emptyList());
        locationDetector = new LocationDetector(mockedContext, "fakeUnique", "fakeId", eventBus);
        Assert.assertFalse(locationDetector.isEnabled());
    }

    @Test
    public void testAvailability() throws Exception {
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_GRANTED);
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_DENIED);
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));

        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_GRANTED);
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_DENIED);
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));

        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_GRANTED);
        Assert.assertTrue(locationDetector.isAvailable());
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_DENIED);
        Assert.assertFalse(locationDetector.isAvailable());
        ShadowLog.setupLogging();
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.PASSIVE_PROVIDER));
        Assert.assertEquals(1, ShadowLog.getLogs().size());
        Assert.assertEquals("Unknown provider:"+LocationManager.PASSIVE_PROVIDER, ShadowLog.getLogs().get(0).msg);
    }

    @Test
    public void testEnabled() throws Exception {
        Assert.assertTrue(locationDetector.isProviderEnabled(LocationManager.GPS_PROVIDER));
        Assert.assertTrue(locationDetector.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
    }

    @Test
    public void testDefaultValues() throws Exception {
        Assert.assertTrue(locationDetector.isValidSet());
        Assert.assertEquals(DetectorType.Position, locationDetector.detectorType());
    }

    @Test
    public void testOnLocationChanged() throws Exception {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        long timestamp = DateTime.now().getMillis();
        location.setTime(timestamp);
        location.setLatitude(10f);
        location.setLongitude(20f);
        location.setAltitude(10f);
        location.setAccuracy(0f);
        Assert.assertEquals(0, locationDetector.sessionValues().size());
        locationDetector.onLocationChanged(location);
        Assert.assertEquals(1, locationDetector.sessionValues().size());
        Assert.assertEquals(""+timestamp+" "+location.getLatitude()+" "+location.getLongitude()+" "+location.getAltitude()+" "+location.getAccuracy()+" "+location.getProvider(), locationDetector.sessionValues().peek());
    }

    @Test
    public void testOnStatusChanged() throws Exception {
        ISensorStatusUpdate sensorStatusUpdate = new ISensorStatusUpdate() {
            @Override
            public void sensorStatusChanged(int sensorType) {
                changedStatus++;
                Assert.assertTrue(sensorType == DetectorType.GpsStatus || sensorType == DetectorType.Position);
            }
        };
        locationDetector.addSensorUpdateListener(sensorStatusUpdate);
        Assert.assertTrue(locationDetector.isAvailable());
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));

        locationDetector.onStatusChanged(LocationManager.GPS_PROVIDER, LocationProvider.TEMPORARILY_UNAVAILABLE, null);
        Assert.assertTrue(locationDetector.isAvailable());
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));
        Assert.assertEquals(1, changedStatus);

        locationDetector.onStatusChanged(LocationManager.NETWORK_PROVIDER, LocationProvider.TEMPORARILY_UNAVAILABLE, null);
        Assert.assertFalse(locationDetector.isAvailable());
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));
        Assert.assertEquals(2, changedStatus);

        locationDetector.onStatusChanged(LocationManager.GPS_PROVIDER, LocationProvider.AVAILABLE, null);
        Assert.assertTrue(locationDetector.isAvailable());
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));
        Assert.assertEquals(3, changedStatus);

        locationDetector.onStatusChanged(LocationManager.NETWORK_PROVIDER, LocationProvider.AVAILABLE, null);
        Assert.assertTrue(locationDetector.isAvailable());
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));
        Assert.assertEquals(4, changedStatus);


        locationDetector.onStatusChanged(LocationManager.GPS_PROVIDER, LocationProvider.OUT_OF_SERVICE, null);
        Assert.assertTrue(locationDetector.isAvailable());
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        Assert.assertTrue(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));
        Assert.assertEquals(5, changedStatus);

        locationDetector.onStatusChanged(LocationManager.NETWORK_PROVIDER, LocationProvider.OUT_OF_SERVICE, null);
        Assert.assertFalse(locationDetector.isAvailable());
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.GPS_PROVIDER));
        Assert.assertFalse(locationDetector.isProviderAvailable(LocationManager.NETWORK_PROVIDER));
        Assert.assertEquals(6, changedStatus);

        locationDetector.removeSensorUpdateListener(sensorStatusUpdate);
    }

    @Test
    public void testStartListeningWithoutPermission() throws Exception {
        // Disregards available/permissions when listening
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_DENIED);
        Assert.assertTrue(locationDetector.startListening());
    }

    @Test
    public void testStartListeningWithPermission() throws Exception {
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_GRANTED);
        Assert.assertTrue(locationDetector.startListening());
    }

    @Test
    public void testStopListeningWithPermission() throws Exception {
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_GRANTED);
        Assert.assertTrue(locationDetector.startListening());
        Assert.assertTrue(locationDetector.isListening());
        locationDetector.stopListening();
        Assert.assertFalse(locationDetector.isListening());
        Mockito.verify(mockedLocationManager, never()).removeUpdates(Mockito.any(LocationListener.class));
    }

    @Test
    public void testStopListeningWithOutPermission() throws Exception {
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_GRANTED);
        Assert.assertTrue(locationDetector.startListening());
        Assert.assertTrue(locationDetector.isListening());
        when(mockedContext.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myPid(), Process.myUid())).thenReturn(PackageManager.PERMISSION_DENIED);
        locationDetector.stopListening();
        Assert.assertFalse(locationDetector.isListening());
        Mockito.verify(mockedLocationManager, Mockito.never()).removeUpdates(Mockito.any(LocationListener.class));
    }
}