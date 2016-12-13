package raunio.gforcetracker;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.annotation.Nullable;

import java.util.List;

/**
 * Created by Tomasz on 30.03.2016.
 */
public class SamplingService extends Service implements SensorEventListener {

    private static final double ACCEL_DEVIATION_LIMIT = 0.05;
    private static final int ACCEL_DEVIATION_LENGTH = 5;
    private static final long DIFF_UPDATE_TIMEOUT = 100L;
    private static final double GYRO_NOISE_LIMIT = 0.06;
    private int rate;
    private SensorManager sensorManager;
    private boolean samplingStarted;
    private int state;
    private IGyroAccel iGyroAccel = null;
    private Sensor accelSensor;
    private Sensor gyroSensor;
    private double gravityAccelLimitLen;
    private long diffTimeStamp;
    private int callibratingLimit;
    private int callibratingAccelCouner;
    private int sampleCounter;
    private long previousTimeStamp;
    private final int CALLIBRATING_LIMIT = 3000;
    private static final int IDX_X=0;
    private static final int IDX_Y=1;
    private static final int IDX_Z=2;
    private double simulatedGravity[] = new double[3];
    public static final int ENGINESTATUS_CALIBRATING = 1;
    public static final int ENGINESTATUS_IDLE =0;
    public static final int ENGINESTATUS_MEASURING = 2;
    private static final int SAMPLECTR_MOD=1000;
    public static final int SENSORTYPE_ACELL=1;
    public static final int SENSORTYPE_NA=0;
    public static final int SENSORTYPE_GYRO=2;
    private double gravityAccelLen;
    private double gravityAccelHighLimit;
    private double gravityAccelLowLimit;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return serviceBinder;
    }


    private final ISamplingService.Stub serviceBinder = new ISamplingService.Stub(){

        @Override
        public void setCallback(IBinder binder) throws RemoteException {
            iGyroAccel = IGyroAccel.Stub.asInterface(binder);
        }

        @Override
        public void removeCallback() throws RemoteException {
            iGyroAccel = null;
        }

        @Override
        public void stopSampling() throws RemoteException {
            SamplingService.this.stopSampling();
            stopSelf();
        }

        @Override
        public boolean isSampling()  {
            return samplingStarted;
        }

        @Override
        public int getState() throws RemoteException {
            return state;
        }
    };


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        stopSampling();
        rate = 10; //SensorManager.SENSOR_DELAY_FASTEST;
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        startSampling();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopSampling();
    }

    private void stopSampling() {
        if (!samplingStarted){
            return;
        }
        if (sensorManager!=null){
            sensorManager.unregisterListener(this);
        }
        samplingStarted = false;
        setState(ENGINESTATUS_IDLE);
    }

    private void setState(int newState) {
        if (state != newState){
            state = newState;
            if (iGyroAccel != null){
                try {
                    iGyroAccel.statusMessage(state);
                } catch (DeadObjectException e){
                    e.printStackTrace();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }else {

            }
        }
    }

    private void startSampling() {
        if (samplingStarted){
            return;
        }
        List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        accelSensor = sensors.size()==0 ? null : sensors.get(0);
        sensors = sensorManager.getSensorList(Sensor.TYPE_GYROSCOPE);
        gyroSensor = sensors.size() == 0 ? null : sensors.get(0);
        initSampling();

        if ((accelSensor!=null)&&(gyroSensor!=null)){
            sensorManager.registerListener(this,accelSensor,rate);
            sensorManager.registerListener(this,gyroSensor,rate);
        }
        samplingStarted=true;
    }

    private void initSampling() {
        sampleCounter = 0;
        previousTimeStamp = -1L;
        //Wartości odnoszące się do stanu kalibracji
        callibratingAccelCouner =0;
        callibratingLimit = CALLIBRATING_LIMIT;
        simulatedGravity[IDX_X]=0.0;
        simulatedGravity[IDX_Y]=0.0;
        simulatedGravity[IDX_Z]=0.0;
        diffTimeStamp =-1L;
        gravityAccelLimitLen = -1;
        setState(ENGINESTATUS_CALIBRATING);
    }




    private String getStateName(int state){
        String stateName = null;
        switch (state){
            case ENGINESTATUS_IDLE:
                stateName ="Idle";
                break;
            case ENGINESTATUS_CALIBRATING:
                stateName="Callibrating";
                break;
            case ENGINESTATUS_MEASURING:
                stateName="Measuring";
                break;
            default:
                stateName="N/A";
                break;
        }

        return stateName;
    }

    private void updateSampleCounter(){
        ++sampleCounter;
        if ((sampleCounter%SAMPLECTR_MOD)==0){
            if (iGyroAccel!=null){
                try {
                    iGyroAccel.sampleCounter(sampleCounter);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        processSample(event);
    }

    private void processSample(SensorEvent event) {
        float values[]=event.values;
        if (values.length<3){
            return;
        }
        String sensorName ="N/A";
        int sensorType = SENSORTYPE_NA;
        if (event.sensor==accelSensor){
            sensorName="accel";
            sensorType=SENSORTYPE_ACELL;
        }else
        if (event.sensor==gyroSensor){
            sensorName = "gyroSensor";
            sensorType=SENSORTYPE_GYRO;
        }
        updateSampleCounter();
        switch (state){
            case ENGINESTATUS_CALIBRATING:
                processCallibrating(event.timestamp,sensorType,values);
                break;
            case ENGINESTATUS_MEASURING:
                processMeasuring(event.timestamp,sensorType,values);
                break;
        }
    }

    private void processMeasuring(long timestamp, int sensorType, float values[]) {
        double dv[]=new double[3];
        dv[IDX_X]=(double)values[0];
        dv[IDX_Y]=(double)values[1];
        dv[IDX_Z]=(double)values[2];
        if (sensorType==SENSORTYPE_ACELL){
            double accelLen = Math.sqrt(
                      dv[IDX_X]*dv[IDX_X]+
                      dv[IDX_Y]*dv[IDX_Y]+
                      dv[IDX_Z]*dv[IDX_Z]
            );
            if ((accelLen<gravityAccelHighLimit)&& (accelLen>gravityAccelLowLimit)){
                if (gravityAccelLimitLen<0){
                    gravityAccelLimitLen=ACCEL_DEVIATION_LENGTH;
                }
                --gravityAccelLimitLen;
                if (gravityAccelLimitLen<=0){
                    gravityAccelLimitLen=0;
                    simulatedGravity[IDX_X]=dv[IDX_X];
                    simulatedGravity[IDX_Y]=dv[IDX_Y];
                    simulatedGravity[IDX_Z]=dv[IDX_Z];
                }
            }else{
                gravityAccelLimitLen=-1;
            }
            double[] diff = vecdiff(dv,simulatedGravity);
            double[] rotatedDiff = rotateToEarth(diff);
            sendDiff(rotatedDiff);
        }else if (sensorType==SENSORTYPE_GYRO) {
            if (previousTimeStamp>=0L){
                double dt=(double)(timestamp-previousTimeStamp)/ 1000000000.0;
                double dx=gyroNoiseLimiter(dv[IDX_X]*dt);
                double dy=gyroNoiseLimiter(dv[IDX_Y]*dt);
                double dz=gyroNoiseLimiter(dv[IDX_Z]*dt);
                rotx(simulatedGravity, -dx);
                roty(simulatedGravity, -dy);
                rotz(simulatedGravity, -dz);
            }
            previousTimeStamp = timestamp;
        }
    }

    private double gyroNoiseLimiter(double gyroValue) {
        double v = gyroValue;
        if (Math.abs(v)<GYRO_NOISE_LIMIT){
            v=0.0;
        }
        return v;
    }

    private double[] rotateToEarth(double diff[]) {
        double rotatedDiff[] = new  double[3];
        rotatedDiff[IDX_X]=diff[IDX_X];
        rotatedDiff[IDX_Y]=diff[IDX_Y];
        rotatedDiff[IDX_Z]=diff[IDX_Z];
        double gravity[] = new double[3];
        gravity[IDX_X]=simulatedGravity[IDX_X];
        gravity[IDX_Y]=simulatedGravity[IDX_Y];
        gravity[IDX_Z]=simulatedGravity[IDX_Z];
        double dz = Math.atan2(gravity[IDX_Y],gravity[IDX_X]);
        dz = fixAtanDegree(dz,gravity[IDX_Y],gravity[IDX_X]);
        rotz(rotatedDiff,-dz);
        rotz(gravity,-dz);
        double dy = Math.atan2(gravity[IDX_X],gravity[IDX_Z]);
        dy = fixAtanDegree(dy,gravity[IDX_X],gravity[IDX_Z]);
        roty(rotatedDiff,-dy);

        return rotatedDiff;
    }

    private double fixAtanDegree(double deg, double y, double x) {
        double rdeg = deg;
        if ((x<0.0)&&(y>0.0)){
            rdeg = Math.PI-deg;
        }
        if ((x<0.0)&&(y<0.0)){
            rdeg = Math.PI+deg;
        }

        return rdeg;
    }

    private void sendDiff(double v[]) {
        long currentTime = System.currentTimeMillis();
        long tdiff = currentTime - diffTimeStamp;
        if ((diffTimeStamp<0L)||(tdiff>DIFF_UPDATE_TIMEOUT)){
            diffTimeStamp = currentTime;
            if (iGyroAccel!=null){
                try {
                    iGyroAccel.diff(v[IDX_X],v[IDX_Y],v[IDX_Z]);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private double[] vecdiff(double v1[], double v2[]) {
        double diff[]=new double[3];
        diff[IDX_X]=v1[IDX_X]-v2[IDX_X];
        diff[IDX_Y]=v1[IDX_Y]-v2[IDX_Y];
        diff[IDX_Z]=v1[IDX_Z]-v2[IDX_Z];

        return diff;
    }

    private void rotz(double vec[], double dz) {
        double x = vec[IDX_X];
        double y = vec[IDX_Y];
        double z = vec[IDX_Z];
        vec[IDX_X]=x*Math.cos(dz)-y*Math.sin(dz);
        vec[IDX_Y]=x*Math.sin(dz)-y*Math.cos(dz);
    }

    private void rotx(double vec[], double dx) {
        double x = vec[IDX_X];
        double y = vec[IDX_Y];
        double z = vec[IDX_Z];
        vec[IDX_Y]=y*Math.cos(dx)-z*Math.sin(dx);
        vec[IDX_Z]=y*Math.sin(dx)-z*Math.cos(dx);
    }

    private void roty(double vec[], double dy) {
        double x = vec[IDX_X];
        double y = vec[IDX_Y];
        double z = vec[IDX_Z];
        vec[IDX_Z]=z*Math.cos(dy)-x*Math.sin(dy);
        vec[IDX_X]=z*Math.sin(dy)-x*Math.cos(dy);
    }

    private void processCallibrating(long timestamp, int sensorType, float values[]) {
        if (sensorType ==SENSORTYPE_ACELL){
            simulatedGravity[IDX_X]+=(double)values[IDX_X];
            simulatedGravity[IDX_Y]+=(double)values[IDX_Y];
            simulatedGravity[IDX_Z]+=(double)values[IDX_Z];
            ++callibratingAccelCouner;
        }
        if (sensorType==SENSORTYPE_GYRO){
            previousTimeStamp=timestamp;
        }
        if (sampleCounter>=callibratingLimit){
            if (callibratingAccelCouner==0){
                callibratingLimit+=CALLIBRATING_LIMIT;
            }else {
                double avgDiv = (double)callibratingAccelCouner;
                simulatedGravity[IDX_X]/=avgDiv;
                simulatedGravity[IDX_Y]/=avgDiv;
                simulatedGravity[IDX_Z]/=avgDiv;
                gravityAccelLen = Math.sqrt(
                        simulatedGravity[IDX_X]*simulatedGravity[IDX_X]+
                                simulatedGravity[IDX_Y]*simulatedGravity[IDX_Y]+
                                simulatedGravity[IDX_Z]*simulatedGravity[IDX_Z]);
                gravityAccelHighLimit = gravityAccelLen*(1.0+ACCEL_DEVIATION_LIMIT);
                gravityAccelLowLimit = gravityAccelLen*(1.0-ACCEL_DEVIATION_LIMIT);
                setState(ENGINESTATUS_MEASURING);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}
