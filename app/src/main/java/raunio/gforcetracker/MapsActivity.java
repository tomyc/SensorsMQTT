package raunio.gforcetracker;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.speech.tts.TextToSpeech;
import android.support.v4.app.FragmentActivity;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONException;

import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class MapsActivity
        extends FragmentActivity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        LocationListener, TextToSpeech.OnInitListener, SensorEventListener
{
    private final int MAX_SAMPLES = 256;

    private GoogleMap map;
    private CameraPosition lastCameraPostion;

    private TextView speed, gforce, distance, lat, lon;
    private CheckBox speech_synthesis;

    private GoogleApiClient googleApi;
    private LocationRequest locationRequest;
    private Location startLocation, lastLocation;

    private TextToSpeech tts;

    private SensorManager sensorManager;
    private Sensor gravitySensor;

    private float maxSpeed, totalDistance;
    private LinkedList<float[]> sensorSamples;
    private LinkedList<Float> speedSamples;

    private Storage storage;
    private Object storageHandle;

    private ContentResolver cr; //zmienna do odczytu ID urządzenia w klasie Sorage

    public static BallPanel ballPanel = null;
    private TextView sampleCounterTV;
    private String sampleCounterText = null;
    private boolean samplingServiceRunning = false;
    private boolean samplingServiceActivated = false;
    private ISamplingService samplingService=null;
    private int state = SamplingService.ENGINESTATUS_IDLE;
    private TextView statusMessageTV;
    private SamplingServiceConnection samplingServiceConnection = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        
        bindSamplingService();
        
        ballPanel = (BallPanel)findViewById(R.id.ballPanel);

        cr = getContentResolver(); //do ustawienia kontekstu przy odczycie ID urządzenia
        
        locationRequest = new LocationRequest();

        locationRequest.setInterval(LocationRequest.PRIORITY_NO_POWER); //lub 5000 gdy ważne jest oszczędzanie ebergii, wtedy pobieranie lokalizacji będzie co 5 sec.
        locationRequest.setFastestInterval(1000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        tts = new TextToSpeech(this, this);

        // ATTENTION: This "addApi(AppIndex.API)"was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        googleApi = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .addApi(com.google.android.gms.appindexing.AppIndex.API).build();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        updateSensorName(R.id.accelsensorname,sensorManager,Sensor.TYPE_ACCELEROMETER,"Accelerometer");
        updateSensorName(R.id.gyrosensorname, sensorManager, Sensor.TYPE_GYROSCOPE, "Gyroscope");

        sampleCounterTV = (TextView)findViewById(R.id.samplecounter);
        if (sampleCounterText !=null){
            sampleCounterTV.setText(sampleCounterText);
        }
        ballPanel = (BallPanel)findViewById(R.id.ballPanel);
        statusMessageTV = (TextView)findViewById(R.id.statusMessageTv);
        CheckBox cb = (CheckBox)findViewById(R.id.samplingAccelGyro);
        if (samplingServiceActivated){
            cb.setChecked(true);
        }else {
            stopSamplingService();
        }
        cb.setOnClickListener(new View.OnClickListener(){

            @Override
            public void onClick(View view) {
                CheckBox cb = (CheckBox)view;
                boolean isChecked = cb.isChecked();
                if(isChecked){
                    samplingServiceActivated = true;
                    statSamplingService();
                    sampleCounterTV.setText(sampleCounterText);
                }else {
                    samplingServiceActivated = false;
                    stopSamplingService();
                }
            }
        });

        storage = new Storage();

        maxSpeed = 0.f;
        totalDistance = 0.f;
        sensorSamples = new LinkedList<>();
        speedSamples = new LinkedList<>();

        initialize();
    }

    private void bindSamplingService() {
        samplingServiceConnection = new SamplingServiceConnection();
        Intent i = new Intent();
        i.setClassName("raunio.gforcetracker","raunio.gforcetracker.SamplingService");
        bindService(i,samplingServiceConnection,Context.BIND_AUTO_CREATE);
    }

    private void stopSamplingService() {
        if (samplingServiceRunning){
            stopSampling();
            samplingServiceRunning = false;
        }
    }

    private void stopSampling() {
        if (samplingService!=null){
            try {
                samplingService.stopSampling();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void statSamplingService() {
        if (samplingServiceRunning){
            stopSamplingService();
        }
        sampleCounterText="0";
        Intent i = new Intent();
        i.setClassName("raunio.gforcetracker","raunio.gforcetracker.SamplingService");
        startService(i);
        samplingServiceRunning = true;
    }

    private void updateSensorName(int tvID, SensorManager sensorManager, int sensorType, String sensorName){
        TextView tv = (TextView)findViewById(tvID);
        List<Sensor> sensors = sensorManager.getSensorList(sensorType);
        StringBuffer sb = new StringBuffer();
        if (sensors.size()==0){
            sb.append(sensorName);
            sb.append(": N/A");
        }else{
            Sensor s = sensors.get(0);
            sb.append(s.getName());
        }
        tv.setText(new String(sb));
    }


    private IGyroAccel.Stub iSteps = new IGyroAccel.Stub() {

        @Override
        public void sampleCounter(int count) throws RemoteException {
            //Log.d(LOG_TAG, "sample count: " + count);
            sampleCounterText = Integer.toString( count );
            sampleCounterTV.setText( sampleCounterText );
        }

        public void statusMessage( int newState ) {
            state = newState;
            if( statusMessageTV  != null ) {
                statusMessageTV.setText( getStateName( newState ) );
            }
            switch( state ) {
                case SamplingService.ENGINESTATUS_CALIBRATING:
                case SamplingService.ENGINESTATUS_IDLE:
                    ballPanel.setVisibility( View.INVISIBLE);
                    break;

                case SamplingService.ENGINESTATUS_MEASURING:
                    ballPanel.setVisibility( View.VISIBLE);
                    break;
            }
        }

        @Override
        public void diff(double x, double y, double z) throws RemoteException {
            if( ballPanel != null ) {
                SurfaceHolder holder = ballPanel.getHolder();
                Canvas c = holder.lockCanvas();
                if( c != null ) {
                    ballPanel.drawBall(c, true, (float)x, (float)y, (float)z);
                    holder.unlockCanvasAndPost(c);
                }
            }
        }
    };

    private String getStateName(int state) {
        String stateName = null;
        switch (state){
            case SamplingService.ENGINESTATUS_IDLE:
                stateName ="Idle";
                break;
            case SamplingService.ENGINESTATUS_CALIBRATING:
                stateName="Calibrating";
                break;
            case SamplingService.ENGINESTATUS_MEASURING:
                stateName="Measuring";
                break;
            default:
                stateName="N/A";
                break;
        }
        return stateName;
    }

    @Override
    protected void onResume() {
        super.onResume();
        initialize();
    }

    @Override
    protected void onDestroy()  {

        // Write last location.
        if (lastLocation != null) {
            float ax = getAverageSensor(0);
            float ay = getAverageSensor(1);
            float az = getAverageSensor(2);
            float axMax = getMaxSensor(0);
            float ayMax = getMaxSensor(1);
            float azMax = getMaxSensor(2);
            float speed = getAverageSpeed();
            float maxSpeed = getMaxSpeed();

            try {
                storage.write(storageHandle, cr, new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()), speed, maxSpeed, ax, ay, az, axMax, ayMax, azMax);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        super.onDestroy();
        releaseSamplingService();
    }

    private void releaseSamplingService() {
        releaseCallbackOnService();
        unbindService(samplingServiceConnection);
        samplingServiceConnection = null;
    }

    private void releaseCallbackOnService() {
        if (samplingService!=null){
            try {
                samplingService.removeCallback();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        LocationServices.FusedLocationApi.requestLocationUpdates(googleApi, locationRequest, this);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        new AlertDialog.Builder(this)
                .setTitle("Google API Client")
                .setMessage("Google API Client is not available, error code: " + connectionResult.getErrorCode())
                .setNeutralButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS && tts.isLanguageAvailable(new Locale("pl_PL")) == TextToSpeech.LANG_AVAILABLE) { //(status == TextToSpeech.SUCCESS && tts.isLanguageAvailable(Locale.ENGLISH) == TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(new Locale("pl_PL")); //tts.setLanguage(Locale.ENGLISH);
            speech_synthesis.setEnabled(true);
        } else {
            speech_synthesis.setSelected(false);
            speech_synthesis.setEnabled(false);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());

        if (startLocation != null)
            totalDistance += startLocation.distanceTo(location);

        speed.setText(String.format("%.2f", location.getSpeed()));
        distance.setText(String.format("%.2f", totalDistance));
        lat.setText(latLng.latitude + "");
        lon.setText(latLng.longitude + "");

        if (speedSamples.size() > MAX_SAMPLES)
            speedSamples.removeFirst();

        speedSamples.addLast(location.getSpeed());
        startLocation = location;

        // Write initial location.
        if (lastLocation == null) {
            float ax = getAverageSensor(0);
            float ay = getAverageSensor(1);
            float az = getAverageSensor(2);
            float axMax = getMaxSensor(0);
            float ayMax = getMaxSensor(1);
            float azMax = getMaxSensor(2);
            float speed = getAverageSpeed();
            float maxSpeed = getMaxSpeed();

            try {
                storage.write(storageHandle, cr, latLng, speed, maxSpeed, ax, ay, az, axMax, ayMax, azMax);
            } catch (JSONException e) {
                e.printStackTrace();
            }

            lastLocation = location;
        }

        // Draw and store route to current location if moved more than 10m from last location. Also,
        // clear the sensor and speed samples.
        if (location.distanceTo(lastLocation) >= 10.f) {
            PolylineOptions lineOptions = new PolylineOptions();

            float ax = getAverageSensor(0);
            float ay = getAverageSensor(1);
            float az = getAverageSensor(2);
            float axMax = getMaxSensor(0);
            float ayMax = getMaxSensor(1);
            float azMax = getMaxSensor(2);
            double a = getTotalAcceleration(new float[] {ax, ay, az});
            double aMax = getTotalAcceleration(new float[] {axMax, ayMax, azMax});
            float speed = getAverageSpeed();
            float maxSpeed = getMaxSpeed();

            // Select line color depending on average G-force.
            if (a >= 4.f)
                lineOptions.color(Color.RED);
            else if (a >= 2.5f)
                lineOptions.color(Color.YELLOW);
            else
                lineOptions.color(Color.GREEN);

            // Map speed to line width so that greater speed equals thicker line. Like:
            // ax + b = y
            // y(11) = 5px
            // y(44) = 18px
            // max(y) = 20px
            // min(y) = 5px

            lineOptions.width(Math.max(Math.min((4.f + 1.f / 3.f) / 11.f * speed + 2.f / 3.f, 20), 5));
            lineOptions.visible(true);

            lineOptions.add(new LatLng(lastLocation.getLatitude(), lastLocation.getLongitude()));
            lineOptions.add(latLng);

            map.addPolyline(lineOptions);

            if (maxSpeed > this.maxSpeed) {
                map.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title(String.format("%.2f m/s", maxSpeed))
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)));

                Say(String.format(new Locale("pl_PL"), "Twoja maksymalna obenie prędkość wynosi %.2f meterów na sekundę.", maxSpeed));
                this.maxSpeed = maxSpeed;
            }

            if (aMax >= 2.5f) {
                MarkerOptions options = new MarkerOptions()
                        .position(latLng)
                        .title(String.format("%.2f G", aMax));

                if (aMax >= 4.f)
                    options.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
                else
                    options.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW));

                map.addMarker(options);

                Say(String.format(new Locale("pl_PL"), "Maksymalne przeciążenie na ostatnim odcinku wynosi %.2f.", aMax));
            }

            try {
                storage.write(storageHandle, cr, latLng, speed, maxSpeed, ax, ay, az, axMax, ayMax, azMax);
            } catch (JSONException e) {
                e.printStackTrace();
            }

            lastLocation = location;

            sensorSamples.clear();
            speedSamples.clear();
        }

        // Update camera position if nobody hasn't touched it.
        if (lastCameraPostion == null || map.getCameraPosition().equals(lastCameraPostion)) {
            float avgSpeed = getAverageSpeed(); //odczyt średniej prędkości
            int mapZoom; //powiększenie aktualnego widoku mapy
            if (avgSpeed>=15.f){
                mapZoom = 10;
            }else {
                mapZoom = 18;
            }
            CameraPosition nowaPozycja = new CameraPosition.Builder()
                    .target(latLng)
                    .zoom(mapZoom) //aktualne powiększenie
                    .bearing(location.getBearing())
                    .tilt(45) //pochylenie mapy
                    .build();

            CameraUpdate cameraUpdate = CameraUpdateFactory.newCameraPosition(nowaPozycja);//CameraUpdateFactory.newLatLngZoom(latLng, 15);


            map.getUiSettings().setAllGesturesEnabled(false);


            map.animateCamera(cameraUpdate, new GoogleMap.CancelableCallback() {
                @Override
                public void onFinish() {
                    lastCameraPostion = map.getCameraPosition();
                    map.getUiSettings().setAllGesturesEnabled(true);
                }

                @Override
                public void onCancel() {
                    onFinish();
                }
            });
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (sensorSamples.size() > MAX_SAMPLES)
            sensorSamples.removeFirst();

        sensorSamples.addLast(event.values);

        gforce.setText(String.format("%.2f", getTotalAcceleration(event.values)));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @TargetApi(21)
    private void Say(String text) {
        if (speech_synthesis.isEnabled() && speech_synthesis.isChecked())
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode() + "");
    }

    @TargetApi(21)
    private void toggleSpeechSynthesis(boolean on) {
        if (on)
            tts.speak("Komunikaty głosowe włączone", TextToSpeech.QUEUE_FLUSH, null, "start");
        else
            tts.speak("Komunikaty głosowe wyłączone", TextToSpeech.QUEUE_FLUSH, null, "end");
    }

    private double getTotalAcceleration(float[] values) {
        return Math.sqrt(values[0] * values[0] +
                         values[1] * values[1] +
                         values[2] * values[2]) / SensorManager.GRAVITY_EARTH;
    }

    private float getAverageSensor(int index) {
        float sum = 0;

        for (float[] value : sensorSamples)
            sum += Math.abs(value[index]);

        return sum / sensorSamples.size();
    }

    private float getAverageSpeed() {
        float sum = 0;

        for (Float value : speedSamples)
            sum += value;

        return sum / speedSamples.size();
    }

    private float getMaxSensor(int index) {
        float max = Float.MIN_VALUE;

        for (float[] value : sensorSamples)
            max = Math.max(Math.abs(value[index]), max);

        return max;
    }

    private float getMaxSpeed() {
        float max = Float.MIN_VALUE;

        for (Float value : speedSamples)
            max = Math.max(value, max);

        return max;
    }

    private void initialize() {
        lastCameraPostion = null;

        if (map == null) {
            map = ((SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map))
                    .getMap();

            map.setMyLocationEnabled(true);
            map.getUiSettings().setAllGesturesEnabled(false);
            map.setMapType(GoogleMap.MAP_TYPE_NORMAL);
            map.setTrafficEnabled(true);

           /* lastCameraPostion = new CameraPosition.Builder()
                    .target(latLng)
                    .zoom(12)
                    .bearing(90)
                    .tilt(30)
                    .build();*/


        }

        if (gravitySensor == null) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

            sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        if (speed == null)
            speed = (TextView) findViewById(R.id.speed);

        if (gforce == null)
            gforce = (TextView) findViewById(R.id.gforce);

        if (distance == null)
            distance = (TextView) findViewById(R.id.distance);

        if (lat == null)
            lat = (TextView) findViewById(R.id.lat);

        if (lon == null)
            lon = (TextView) findViewById(R.id.lon);

        if (speech_synthesis == null) {
            speech_synthesis = (CheckBox) findViewById(R.id.speech_synthesis);

            speech_synthesis.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleSpeechSynthesis(((CheckBox) v).isChecked());
                }
            });
        }

        if (storageHandle == null)
            storageHandle = storage.create();

        if (!googleApi.isConnected())
            googleApi.connect();
    }

    @Override
    public void onStart() {
        super.onStart();

        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        googleApi.connect();
        com.google.android.gms.appindexing.Action viewAction = com.google.android.gms.appindexing.Action.newAction(
                com.google.android.gms.appindexing.Action.TYPE_VIEW, // TODO: choose an action type.
                "Maps Page", // TODO: Define a title for the content shown.
                // TODO: If you have web page content that matches this app activity's content,
                // make sure this auto-generated web page URL is correct.
                // Otherwise, set the URL to null.
                Uri.parse("http://host/path"),
                // TODO: Make sure this auto-generated app deep link URI is correct.
                Uri.parse("android-app://raunio.gforcetracker/http/host/path")
        );
        com.google.android.gms.appindexing.AppIndex.AppIndexApi.start(googleApi, viewAction);
    }

    @Override
    public void onStop() {
        super.onStop();

        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        com.google.android.gms.appindexing.Action viewAction = com.google.android.gms.appindexing.Action.newAction(
                com.google.android.gms.appindexing.Action.TYPE_VIEW, // TODO: choose an action type.
                "Maps Page", // TODO: Define a title for the content shown.
                // TODO: If you have web page content that matches this app activity's content,
                // make sure this auto-generated web page URL is correct.
                // Otherwise, set the URL to null.
                Uri.parse("http://host/path"),
                // TODO: Make sure this auto-generated app deep link URI is correct.
                Uri.parse("android-app://raunio.gforcetracker/http/host/path")
        );
        com.google.android.gms.appindexing.AppIndex.AppIndexApi.end(googleApi, viewAction);
        googleApi.disconnect();
    }

    private class SamplingServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            samplingService = ISamplingService.Stub.asInterface((IBinder)iBinder);
            setCallbackOnService();
            updateSampleServiceRunning();
            updateState();
            CheckBox checkBox = (CheckBox)findViewById(R.id.samplingAccelGyro);
            checkBox.setChecked(samplingServiceRunning);
            if (statusMessageTV!=null){
                statusMessageTV.setText(getStateName(state));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            samplingService = null;
        }
    }

    private void updateState() {
        if (samplingService!=null){
            try {
                state=samplingService.getState();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void updateSampleServiceRunning() {
        if (samplingService!=null){
            try {
                samplingServiceRunning=samplingService.isSampling();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void setCallbackOnService() {
        if (samplingService!=null){
            try {
                samplingService.setCallback(iSteps.asBinder());
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
