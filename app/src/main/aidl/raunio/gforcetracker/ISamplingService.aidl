// ISamplingService.aidl
package raunio.gforcetracker;

// Declare any non-default types here with import statements

interface ISamplingService {
   void setCallback( in IBinder binder );
   void removeCallback();
   void stopSampling();
   boolean isSampling();
   int getState();
}
