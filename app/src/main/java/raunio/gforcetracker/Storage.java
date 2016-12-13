package raunio.gforcetracker;

import android.content.ContentResolver;
import android.os.Environment;
import android.provider.Settings;

import com.google.android.gms.maps.model.LatLng;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

public class Storage {

    private String path;

    public static final String BROKER_URL = "tcp://iot.eclipse.org:1883";
    public static final String TOPIC = "gforce/data";
    //public Long timestamp = System.currentTimeMillis()/1000;

    private MqttClient client;

    public Storage() {
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GForceTracker");

        file.mkdirs();

        path = file.getPath();

    }

    public Object create() {
        File file = new File(path, String.format("%tFT%<tRZ.json", Calendar.getInstance(TimeZone.getTimeZone("Z"))));

        try {
            if (!file.createNewFile() || !file.canWrite())
                file = null;
        } catch (IOException e) {
            file = null;
        }

        return file;
    }

    public void write(Object handle, ContentResolver cr, LatLng latLng, float speed, float maxSpeed, float ax, float ay, float az, float axMax, float ayMax, float azMax) throws JSONException {
        if (handle == null)
            return;

        File file = (File)handle;
        String Jason2Text;
        //JSONArray data = new JSONArray();
        JSONObject point;
        Long timestamp = System.currentTimeMillis()/1000;
        String android_id = Settings.Secure.getString(cr, Settings.Secure.ANDROID_ID);
        point = new JSONObject();
        point.put("Time",timestamp);
        point.put("DeviceId",android_id);
        point.put("Latitude",latLng.latitude);
        point.put("Longitude", latLng.longitude);
        point.put("Speed",speed);
        point.put("MaxSpeed",String.format(Locale.US, "%.6f",maxSpeed));
        point.put("XAxis",ax);
        point.put("YAxis",ay);
        point.put("ZAxis",az);
        point.put("XAxisMax",axMax);
        point.put("YAxisMax",ayMax);
        point.put("ZAxisMax",azMax);
        //data.put(point);
        //Jason2Text = data.toString();
        Jason2Text = point.toString();


        try {
            client = new MqttClient(BROKER_URL, MqttClient.generateClientId(), new MemoryPersistence());
            client.connect();
        } catch (MqttException e) {
            System.out.println("Błąd połaczenia");
            e.printStackTrace();
        }

        if(client.isConnected()) {
            try {
                final MqttTopic mqttTopic = client.getTopic(TOPIC);

                MqttMessage message = new MqttMessage(Jason2Text.getBytes());
                mqttTopic.publish(message);
                client.disconnect();
                //System.out.println("Envois de : " + message);
            } catch (MqttException e) {
                System.out.println("Erreur : envois du message.");
                e.printStackTrace();
            }
        }


        try {
            FileOutputStream stream = new FileOutputStream((File)handle, true);

            stream.write(Jason2Text.getBytes());

            // Write header if file is empty.
           /* if (file.length() == 0)
                stream.write("Timestamp\tLat\tLon\tSpeed\tMax speed\tax\tay\taz\tMax ax\tMax ay\tMax az".getBytes());*/

           /* stream.write(String.format(
                    Locale.ENGLISH, "%tFT%<tRZ\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f\t%f",
                    Calendar.getInstance(TimeZone.getTimeZone("Z")),
                    latLng.latitude,
                    latLng.longitude,
                    speed,
                    maxSpeed,
                    ax, ay, az,
                    axMax, ayMax, azMax).getBytes());*/

            stream.close();
        } catch (IOException e) {

        }
    }

}
