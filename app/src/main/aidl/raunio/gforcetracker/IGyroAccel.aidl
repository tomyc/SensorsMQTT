// IGyroAccel.aidl
package raunio.gforcetracker;

// Declare any non-default types here with import statements

interface IGyroAccel {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */

     oneway void sampleCounter( in int count );
     oneway void statusMessage( in int state );
     oneway void diff( in double x, in double y, in double z );

}
