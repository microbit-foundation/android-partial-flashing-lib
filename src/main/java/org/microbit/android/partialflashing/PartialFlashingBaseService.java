package org.microbit.android.partialflashing;

import android.app.Activity;
import android.app.IntentService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;

/**
 * A class to communicate with and flash the micro:bit without having to transfer the entire HEX file
 * Created by samkent on 07/11/2017.
 */

// A service that interacts with the BLE device via the Android BLE API.
public abstract class PartialFlashingBaseService extends IntentService {

    public static final UUID PARTIAL_FLASH_CHARACTERISTIC = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8");
    public static final UUID PARTIAL_FLASHING_SERVICE = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8");
    public static final String PXT_MAGIC = "708E3B92C615A841C49866C975EE5197";

    private static final UUID MICROBIT_DFU_SERVICE = UUID.fromString("e95d93b0-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_DFU_CHARACTERISTIC = UUID.fromString("e95d93b1-251d-470a-a062-fa1922dfa9a8");

    private final static String TAG = PartialFlashingBaseService.class.getSimpleName();
    
    public static final String BROADCAST_ACTION = "org.microbit.android.partialflashing.broadcast.BROADCAST_ACTION";

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothDevice device;
    BluetoothGattService Service;
    BluetoothGattService dfuService;

    BluetoothGattCharacteristic partialFlashCharacteristic;

    private final Object lock = new Object();

    private static final byte PACKET_STATE_WAITING = 0;
    private static final byte PACKET_STATE_SENT = (byte)0xFF;
    private static final byte PACKET_STATE_RETRANSMIT = (byte)0xAA;
    private byte packetState = PACKET_STATE_WAITING;

    // Used to lock the program state while we wait for a Bluetooth operation to complete
    private static final boolean BLE_WAITING = false;
    private static final boolean BLE_READY = true;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Regions
    private static final int REGION_SD = 0;
    private static final int REGION_DAL = 1;
    private static final int REGION_MAKECODE = 2;

    // DAL Hash
    String dalHash;

    // Partial Flashing Commands
    private static final byte REGION_INFO_COMMAND = 0x0;
    private static final byte FLASH_COMMAND = 0x1;
    Boolean notificationReceived;

    // Regions
    String[] regions = {"SoftDevice", "DAL", "MakeCode"};

    // Partial Flashing Return Vals
    private static final int PF_SUCCESS = 0x0;
    private static final int PF_ATTEMPT_DFU = 0x1;
    private static final int PF_FAILED = 0x2;

    public final static String ACTION_GATT_CONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED =
            "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED =
            "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE =
            "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "com.example.bluetooth.le.EXTRA_DATA";

    @Override
    public void onCreate() {
        super.onCreate();

        // Create intent filter and add to Local Broadcast Manager so that we can use an Intent to 
        // start the Partial Flashing Service
        
        final IntentFilter intentFilter = new IntentFilter();                           
        intentFilter.addAction(PartialFlashingBaseService.BROADCAST_ACTION);                       
        
        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(broadcastReceiver, intentFilter);
    }

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                    int newState) {
                    String intentAction;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        intentAction = ACTION_GATT_CONNECTED;
                        mConnectionState = STATE_CONNECTED;
                        Log.i(TAG, "Connected to GATT server.");
                        Log.i(TAG, "Attempting to start service discovery:" +
                                gatt.discoverServices());

                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        intentAction = ACTION_GATT_DISCONNECTED;
                        mConnectionState = STATE_DISCONNECTED;
                        Log.i(TAG, "Disconnected from GATT server.");
                    }
                }

                @Override
                // New services discovered
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "onServicesDiscovered SUCCESS");
                    } else {
                        Log.w(TAG, "onServicesDiscovered received: " + status);
                    }

                    Service = gatt.getService(PARTIAL_FLASHING_SERVICE);
                    if (Service == null) {
                        Log.e(TAG, "pf service not found!");
                    }

                    dfuService = gatt.getService(MICROBIT_DFU_SERVICE);
                    if (dfuService == null) {
                        Log.e(TAG, "dfu service not found!");
                    }

                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }

                @Override
                // Result of a characteristic read operation
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    Log.v(TAG, String.valueOf(status));
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic,
                                                int status){
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        // Success
                        Log.v(TAG, "GATT status: Success");
                    } else {
                        // TODO Attempt to resend?
                        Log.v(TAG, "GATT status:" + Integer.toString(status));
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                    super.onCharacteristicChanged(gatt, characteristic);
                    byte notificationValue[] = characteristic.getValue();
                    Log.v(TAG, "Received Notification: " + bytesToHex(notificationValue));

                    // What command
                    switch(notificationValue[0])
                    {
                        case REGION_INFO_COMMAND: {
                            // Get Hash + Start / End addresses
                            Log.v(TAG, "Region: " + notificationValue[1]);

                            byte[] startAddress = Arrays.copyOfRange(notificationValue, 2, 6);
                            byte[] endAddress = Arrays.copyOfRange(notificationValue, 6, 10);
                            Log.v(TAG, "startAddress: " + bytesToHex(startAddress) + " endAddress: " + bytesToHex(endAddress));

                            byte[] hash = Arrays.copyOfRange(notificationValue, 10, 18);
                            Log.v(TAG, "Hash: " + bytesToHex(hash));

                            // If Region is DAL get HASH
                            if (notificationValue[1] == REGION_DAL)
                                dalHash = bytesToHex(hash);

                            break;
                        }
                        case FLASH_COMMAND: {
                            packetState = notificationValue[1];
                        }
                    }

                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }

                @Override
                public void onDescriptorWrite (BluetoothGatt gatt,
                                        BluetoothGattDescriptor descriptor,
                                        int status){
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        Log.v(TAG, "Descriptor success");
                    }
                    Log.v(TAG, "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);

                    synchronized (lock) {
                        lock.notifyAll();
                    }

                }

            };

    public PartialFlashingBaseService() {
      super(TAG);
    }

    public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
    public static final String BROADCAST_START = "org.microbit.android.partialflashing.broadcast.BROADCAST_START";
    public static final String BROADCAST_COMPLETE = "org.microbit.android.partialflashing.broadcast.BROADCAST_COMPLETE";
    public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";
    
    private void sendProgressBroadcast(final int progress) {

        Log.v(TAG, "Sending progress broadcast: " + progress + "%");

        final Intent broadcast = new Intent(BROADCAST_PROGRESS);
        broadcast.putExtra(EXTRA_PROGRESS, progress);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }
    
    private void sendProgressBroadcastStart() {

        Log.v(TAG, "Sending progress broadcast start");

        final Intent broadcast = new Intent(BROADCAST_START);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }
    
    private void sendProgressBroadcastComplete() {

        Log.v(TAG, "Sending progress broadcast complete");

        final Intent broadcast = new Intent(BROADCAST_COMPLETE);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    // Write to BLE Flash Characteristic
    public Boolean writePartialFlash(byte data[]){

        partialFlashCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        partialFlashCharacteristic.setValue(data);
        boolean status = mBluetoothGatt.writeCharacteristic(partialFlashCharacteristic);
        return status;

    }

    public int attemptPartialFlash(String filePath) {
        Log.v(TAG, "Flashing: " + filePath);
        long startTime = SystemClock.elapsedRealtime();

        int count = 0;
        int progressBar = 0;
        int numOfLines = 0;

        try {

            Log.v(TAG, "attemptPartialFlash()");
            Log.v(TAG, filePath);
            HexUtils hex = new HexUtils(filePath);
            int magicIndex = hex.searchForData(PXT_MAGIC);
            if (magicIndex > -1) {
                
                Log.v(TAG, "Found PXT_MAGIC");
                
                // Find DAL hash
                String hashes = hex.getDataFromIndex(magicIndex + 1);
                if(!hashes.substring(0, 16).equals(dalHash)) {
                        Log.v(TAG, hashes.substring(0, 16) + " " + (dalHash));
                        return PF_ATTEMPT_DFU;
                }

                numOfLines = hex.numOfLines() - magicIndex;
                Log.v(TAG, "Total lines: " + numOfLines);

                // Ready to flash!
                sendProgressBroadcastStart();
                // Loop through data
                String hexData;
                int packetNum = 0;
                int lineCount = 0;
                while(true){
                    // Timeout if total is > 60 seconds
                    if(SystemClock.elapsedRealtime() - startTime > 60000) return PF_FAILED;

                    // Get next data to write
                    hexData = hex.getDataFromIndex(magicIndex + lineCount);

                    Log.v(TAG, hexData);

                    // Check if EOF
                    if(hex.getRecordTypeFromIndex(magicIndex + lineCount) != 0) break;

                    // Split into bytes
                    int offsetToSend = 0;
                    if(count == 0) {
                        offsetToSend = hex.getRecordAddressFromIndex(magicIndex + lineCount);
                    }

                    if(count == 1) {
                        offsetToSend = hex.getSegmentAddress(magicIndex + lineCount);
                    }

                    byte chunk[] = HexUtils.recordToByteArray(hexData, offsetToSend, packetNum);

                    // Write without response
                    // Wait for previous write to complete
                    boolean writeStatus = writePartialFlash(chunk);

                    // Sleep after 4 packets
                    count++;
                    if(count == 4){
                        count = 0;
                        
                        // Wait for notification
                        Log.v(TAG, "Wait for notification");
                        
                        // Send broadcast while waiting
                        int percent = Math.round((float)100 * ((float)(lineCount) / (float)(numOfLines)));
                        sendProgressBroadcast(percent);

                        long timeout = SystemClock.elapsedRealtime();
                        while(packetState == PACKET_STATE_WAITING) {
                            synchronized (lock) {
                                lock.wait(1);
                            }

                            // Timeout if longer than 5 seconds
                            if((SystemClock.elapsedRealtime() - timeout) > 5000) return PF_FAILED;
                        }

                        packetState = PACKET_STATE_WAITING;

                        Log.v(TAG, "/Wait for notification");

                    } else {
                        Thread.sleep(3);
                    }

                    // If notification is retransmit -> retransmit last block.
                    // Else set start of new block
                    if(packetState == PACKET_STATE_RETRANSMIT) {
                        lineCount = lineCount - 4;
                    } else {
                        // Next line
                        lineCount = lineCount + 1;
                    }

                    // Always increment packet #
                    packetNum = packetNum + 1;

                }



                // Write End of Flash packet
                byte[] endOfFlashPacket = {(byte)0x02};
                writePartialFlash(endOfFlashPacket);

                // Finished Writing
                Log.v(TAG, "Flash Complete");
                sendProgressBroadcast(100);
                sendProgressBroadcastComplete();

                // Time execution
                long endTime = SystemClock.elapsedRealtime();
                long elapsedMilliSeconds = endTime - startTime;
                double elapsedSeconds = elapsedMilliSeconds / 1000.0;
                Log.v(TAG, "Flash Time: " + Float.toString((float)elapsedSeconds) + " seconds");

            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


        return PF_SUCCESS;
    }

    /**
     * Initializes bluetooth adapter
     *
     * @return <code>true</code> if initialization was successful
     */
    private boolean initialize(String deviceId) throws InterruptedException {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            Log.e(TAG, "Unable to initialize BluetoothManager.");
            return false;
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        if (!mBluetoothAdapter.isEnabled())
            return false;

        Log.v(TAG,"Connecting to the device...");
        if (device == null) {
            device = mBluetoothAdapter.getRemoteDevice(deviceId);
        }

        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);

        //check mBluetoothGatt is available
        if (mBluetoothGatt == null) {
            Log.e(TAG, "lost connection");
            return false;
        }

        synchronized (lock) {
            lock.wait(2000);
        }

        // Get Characteristic
        partialFlashCharacteristic = Service.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC);


        // Set up BLE notification
        if(!mBluetoothGatt.setCharacteristicNotification(partialFlashCharacteristic, true)){
            Log.e(TAG, "Failed to set up notifications");
        } else {
            Log.v(TAG, "Notifications enabled");
        }

        // Set up BLE priority
        if(mBluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)){
            Log.w(TAG, "Failed to set up priority");
        } else {
            Log.v(TAG, "High priority");
        }

        BluetoothGattDescriptor descriptor = partialFlashCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        descriptor.setValue(ENABLE_NOTIFICATION_VALUE);
        boolean res = mBluetoothGatt.writeDescriptor(descriptor);

        synchronized (lock) {
            lock.wait(2000);
        }

        return true;
    }
    
    public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        Log.v(TAG, "Start Partial Flash");

        final String filePath = intent.getStringExtra("filepath");
        final String deviceAddress = intent.getStringExtra("deviceAddress");

        Log.v(TAG, "Initialise");
        try {
            initialize(deviceAddress);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.v(TAG, "/Initialise");
        readMemoryMap();
        // If fails attempt full flash
        int pf_result = attemptPartialFlash(filePath);
        if((pf_result == PF_ATTEMPT_DFU) || Service == null)
        {
            Log.v(TAG, "Partial Flashing not possible");

            // Write Characteristic to enter DFU mode
            BluetoothGattCharacteristic microbitDFUCharacteristic = dfuService.getCharacteristic(MICROBIT_DFU_CHARACTERISTIC);
            byte payload[] = {0x01};
            microbitDFUCharacteristic.setValue(payload);
            boolean status = mBluetoothGatt.writeCharacteristic(microbitDFUCharacteristic);
            Log.v(TAG, "MicroBitDFU :: Enter DFU Result " + status);

            // Wait
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            // Refresh services
            try {
                // BluetoothGatt gatt
                final Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
                if (refresh != null) {
                    refresh.invoke(mBluetoothGatt);
                }
            } catch (Exception e) {
                // Log it
            }

            final Intent broadcast = new Intent(BROADCAST_PF_FAILED);

            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        } else if(pf_result == PF_FAILED) {
            // Partial flashing started but failed. Need to PF or USB flash to fix
            final Intent broadcast = new Intent(BROADCAST_PF_FAILED);
            startActivity(broadcast);
        }

        Log.v(TAG, "onHandleIntent End");
    }

    /*
    Read Memory Map from the MB
     */
    private Boolean readMemoryMap() {
        boolean status; // Gatt Status

        try {
            for (int i = 0; i < 3; i++)
            {
                // Get Start, End, and Hash of each Region
                // Request Region
                byte[] payload = {REGION_INFO_COMMAND, (byte)i};
                partialFlashCharacteristic.setValue(payload);
                notificationReceived = false;
                status = mBluetoothGatt.writeCharacteristic(partialFlashCharacteristic);
                Log.v(TAG, "Request Region " + i);
                synchronized (lock) {
                    lock.wait();
                }
            }


        } catch (Exception e){
            Log.e(TAG, e.toString());
        }

        return true;
    }

    public static String bytesToHex(byte[] bytes) {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    /* Receive updates on user interaction */
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, final Intent intent) {
            Log.v(TAG, "Received Broadcast: " + intent.toString());
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "onDestroy");
        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.unregisterReceiver(broadcastReceiver);
    }
    
    protected abstract Class<? extends Activity> getNotificationTarget();
}

