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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.text.style.UpdateLayout;
import android.util.Log;
import android.util.TimingLogger;

import org.microbit.android.partialflashing.HexUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Timer;
import java.util.UUID;

/**
 * A class to communicate with and flash the micro:bit without having to transfer the entire HEX file
 * Created by samkent on 07/11/2017.
 */

// A service that interacts with the BLE device via the Android BLE API.
public abstract class PartialFlashingBaseService extends IntentService {

    public static final UUID PARTIAL_FLASH_CHARACTERISTIC = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8");
    public static final UUID PARTIAL_FLASHING_SERVICE = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8");
    
    public static final String PXT_MAGIC = "708E3B92C615A841C49866C975EE5197";

    private final static String TAG = PartialFlashingBaseService.class.getSimpleName();
    
    public static final String BROADCAST_ACTION = "org.microbit.android.partialflashing.broadcast.BROADCAST_ACTION";

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothDevice device;
    BluetoothGattService Service;

    BluetoothGattCharacteristic partialFlashCharacteristic;

    private static final byte PACKET_STATE_WAITING = 0;
    private static final byte PACKET_STATE_SENT = (byte)0xFF;
    private static final byte PACKET_STATE_RETRANSMIT = (byte)0xAA;
    private byte packetState = PACKET_STATE_WAITING;

    // Used to lock the program state while we wait for a Bluetooth operation to complete
    private static final boolean BLE_WAITING = false;
    private static final boolean BLE_READY = true;
    private boolean bluetoothStatus = BLE_WAITING;

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
                                mBluetoothGatt.discoverServices());

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

                    Service = mBluetoothGatt.getService(PARTIAL_FLASHING_SERVICE);
                    if (Service == null) {
                        Log.e(TAG, "service not found!");
                    }
                    bluetoothStatus = BLE_READY;

                }

                @Override
                // Result of a characteristic read operation
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        bluetoothStatus = BLE_READY;
                    }

                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic,
                                                int status){
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        // Success
                        Log.v(TAG, "GATT status: Success");
                        bluetoothStatus = BLE_READY;
                    } else {
                        // TODO Attempt to resend?
                        Log.v(TAG, "GATT status:" + Integer.toString(status));
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
                            Log.v(TAG, "Packet Acknowledged: " + notificationValue[1]);
                            packetState = notificationValue[1];
                        }
                    }

                }

                @Override
                public void onDescriptorWrite (BluetoothGatt gatt,
                                        BluetoothGattDescriptor descriptor,
                                        int status){
                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        Log.v(TAG, "Descriptor success");
                        bluetoothStatus = BLE_READY;
                    }
                    Log.v(TAG, "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);
                }

            };

    public PartialFlashingBaseService() {
      super(TAG);
    }

    public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
    public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";
    
    private void sendProgressBroadcast(final int progress) {

        Log.v(TAG, "Sending progress broadcast: " + progress + "%");

        final Intent broadcast = new Intent(BROADCAST_PROGRESS);
        broadcast.putExtra(EXTRA_PROGRESS, progress);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    // Write to BLE Flash Characteristic
    public Boolean writePartialFlash(byte data[]){

        partialFlashCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        partialFlashCharacteristic.setValue(data);
        boolean status = mBluetoothGatt.writeCharacteristic(partialFlashCharacteristic);
        return status;

    }


    public Boolean attemptPartialFlash(String filePath) {
        Log.v(TAG, "Flashing: filePath");
        long startTime = SystemClock.elapsedRealtime();

        int count = 0;
        int progressBar = 0;
        int numOfLines = 0;
        try {

            Log.v(TAG, "attemptPartialFlash()");

            HexUtils hex = new HexUtils(filePath);
            int magicIndex = hex.searchForData(PXT_MAGIC);
            if (magicIndex > -1) {
                
                Log.v(TAG, "Found PXT_MAGIC");

                numOfLines = hex.numOfLines() - magicIndex;
                Log.v(TAG, "Total lines: " + numOfLines);

                // Ready to flash!
                // Loop through data
                String hexData;
                int packetNum = 0;
                int lineCount = 0;
                while(true){
                    // Get next data to write
                    hexData = hex.getDataFromIndex(magicIndex + lineCount);
                    // Check if EOF
                    if(hex.getRecordTypeFromIndex(magicIndex + lineCount) != 0) break;

                    // Log record being written
                    Log.v(TAG, "Hex Data  : " + hexData);
                    Log.v(TAG, "Hex Offset: " + Integer.toHexString(hex.getRecordAddressFromIndex(magicIndex + lineCount)));

                    // If Hex Data is Embedded Source Magic
                    if(hexData.length() == 32) {
                        if (hexData.substring(0, 15).equals("41140E2FB82FA2B"))
                        {
                            // Start of embedded source
                            Log.v(TAG, "Reached embedded source");
                            // Time execution
                            long endTime = SystemClock.elapsedRealtime();
                            long elapsedMilliSeconds = endTime - startTime;
                            double elapsedSeconds = elapsedMilliSeconds / 1000.0;
                            Log.v(TAG, "Flash Time (No Embedded Source): " + Float.toString((float) elapsedSeconds) + " seconds");
                            break;
                        }
                    }

                    // Split into bytes
                    int offsetToSend = 0;
                    if(count == 0) {
                        offsetToSend = hex.getRecordAddressFromIndex(magicIndex + lineCount);
                    }

                    if(count == 1) {
                        offsetToSend = hex.getSegmentAddress(magicIndex + lineCount);
                    }

                    Log.v(TAG, "OFFSET_TO_SEND: " + offsetToSend);
                    byte chunk[] = recordToByteArray(hexData, offsetToSend, packetNum);

                    // Write without response
                    // Wait for previous write to complete
                    boolean writeStatus = writePartialFlash(chunk);
                    Log.v(TAG, "Hex Write: " + Boolean.toString(writeStatus));

                    // Sleep after 4 packets
                    count++;
                    if(count == 4){
                        count = 0;
                        // Wait for notification
                        while(packetState == PACKET_STATE_WAITING);

                        // Reset to waiting state
                        packetState = PACKET_STATE_WAITING;

                    } else {
                        Thread.sleep(5);
                    }

                    // If notification is retransmit -> retransmit last block.
                    // Else set start of new block
                    if(packetState == PACKET_STATE_RETRANSMIT) {
                        lineCount = lineCount - 4;
                    } else {
                        // Send progress update
                        Log.v(TAG, "LC: " + lineCount + ", NL: " + numOfLines);
                        int percent = Math.round((float)100 * ((float)(lineCount) / (float)(numOfLines)));
                        sendProgressBroadcast(percent);
                        
                        // Next line
                        lineCount = lineCount + 1;
                    }

                    // Increment packet #
                    packetNum = packetNum + 1;

                }

                // Write End of Flash packet
                byte[] endOfFlashPacket = {(byte)0x02};
                writePartialFlash(endOfFlashPacket);

                // Finished Writing
                Log.v(TAG, "Flash Complete");
                sendProgressBroadcast(100);

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

        return true;
    }

    /*
    Record to byte Array
    @param hexString string to convert
    @return byteArray of hex
     */
    private static byte[] recordToByteArray(String hexString, int offset, int packetNum){
        int len = hexString.length();
        byte[] data = new byte[(len/2) + 4];
        for(int i=0; i < len; i+=2){
            data[(i / 2) + 4] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4) + Character.digit(hexString.charAt(i+1), 16));
        }

        // WRITE Command
        data[0] = 0x01;

        data[1]   = (byte)(offset >> 8);
        data[2] = (byte)(offset & 0xFF);
        data[3] = (byte)(packetNum & 0xFF);

        Log.v(TAG, "Sent: " + data.toString());

        return data;
    }

    /**
     * Initializes bluetooth adapter
     *
     * @return <code>true</code> if initialization was successful
     */
    private boolean initialize(String deviceId) {
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

        while(!bluetoothStatus);
        // Get Characteristic
        partialFlashCharacteristic = Service.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC);


        // Set up BLE notification
        if(!mBluetoothGatt.setCharacteristicNotification(partialFlashCharacteristic, true)){
            Log.e(TAG, "Failed to set up notifications");
        } else {
            Log.v(TAG, "Notifications enabled");
        }

        BluetoothGattDescriptor descriptor = partialFlashCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);

        bluetoothStatus = BLE_WAITING;
        mBluetoothGatt.writeDescriptor(descriptor);
        while(!bluetoothStatus);

        return true;
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        Log.v(TAG, "Start Partial Flash");

        final String filePath = intent.getStringExtra("filepath");
        final String deviceAddress = intent.getStringExtra("deviceAddress");

        Log.v(TAG, "Initialise");
        initialize(deviceAddress);
        Log.v(TAG, "/Initialise");
        readMemoryMap();
        // If fails attempt full flash
        if(!attemptPartialFlash(filePath))
        {
            Log.v(TAG, "Partial Flashing not possible");
        }


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
                bluetoothStatus = BLE_WAITING;
                notificationReceived = false;
                status = mBluetoothGatt.writeCharacteristic(partialFlashCharacteristic);
                Log.v(TAG, "Request Region " + i);
                while(!bluetoothStatus);
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
    
    protected abstract Class<? extends Activity> getNotificationTarget();
}

