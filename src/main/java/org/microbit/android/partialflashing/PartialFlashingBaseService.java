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
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/**
 * A class to communicate with and flash the micro:bit without having to transfer the entire HEX file
 * Created by samkent on 07/11/2017.
 *
 * (c) 2017 - 2021, Micro:bit Educational Foundation and contributors
 *
 * SPDX-License-Identifier: MIT
 */

// A service that interacts with the BLE device via the Android BLE API.
public abstract class PartialFlashingBaseService extends IntentService {

    public static final String DFU_BROADCAST_ERROR = "no.nordicsemi.android.dfu.broadcast.BROADCAST_ERROR";

    public static final UUID PARTIAL_FLASH_CHARACTERISTIC = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8");
    public static final UUID PARTIAL_FLASHING_SERVICE = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8");
    public static final String PXT_MAGIC = "708E3B92C615A841C49866C975EE5197";
    public static final String UPY_MAGIC = ".*FE307F59.{16}9DD7B1C1.*";

    private static final UUID MICROBIT_DFU_SERVICE = UUID.fromString("e95d93b0-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_SECURE_DFU_SERVICE = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb");
    private static final UUID MICROBIT_DFU_CHARACTERISTIC = UUID.fromString("e95d93b1-251d-470a-a062-fa1922dfa9a8");

    private final static String TAG = PartialFlashingBaseService.class.getSimpleName();
    
    public static final String BROADCAST_ACTION = "org.microbit.android.partialflashing.broadcast.BROADCAST_ACTION";

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothDevice device;
    private boolean descriptorWriteSuccess = false;

    BluetoothGattService pfService;
    BluetoothGattCharacteristic partialFlashCharacteristic;

    private final Object lock = new Object();
    private final Object region_lock = new Object();

    private static final byte PACKET_STATE_WAITING = 0;
    private static final byte PACKET_STATE_SENT = (byte)0xFF;
    private static final byte PACKET_STATE_RETRANSMIT = (byte)0xAA;
    private static final byte PACKET_STATE_COMPLETE_FLASH = (byte) 0xCF;

    private byte packetState = PACKET_STATE_WAITING;

    // Used to lock the program state while we wait for a Bluetooth operation to complete
    private static final boolean BLE_WAITING = false;
    private static final boolean BLE_READY = true;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTED_AND_READY = 3;
    private static final int STATE_ERROR = 4;

    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Regions
    private static final int REGION_SD = 0;
    private static final int REGION_DAL = 1;
    private static final int REGION_MAKECODE = 2;

    // DAL Hash
    boolean python = false;
    String dalHash;

    // Partial Flashing Commands
    private static final byte REGION_INFO_COMMAND = 0x0;
    private static final byte FLASH_COMMAND = 0x1;
    Boolean notificationReceived;

    // Regions
    String[] regions = {"SoftDevice", "DAL", "MakeCode"};

    // Microbit Type
    private final int MICROBIT_V1 = 1;
    private final int MICROBIT_V2 = 2;
    int hardwareType = MICROBIT_V1;

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

        initialize();
    }

    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                    int newState) {
                    String intentAction;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {

                        final boolean success = gatt.discoverServices();
                        mConnectionState = STATE_CONNECTED;

                        /* Taken from Nordic. See reasoning here: https://github.com/NordicSemiconductor/Android-DFU-Library/blob/e0ab213a369982ae9cf452b55783ba0bdc5a7916/dfu/src/main/java/no/nordicsemi/android/dfu/DfuBaseService.java#L888 */
                        if (gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                            Log.v(TAG, "Already bonded");
                            synchronized (lock) {
                                try {
                                    lock.wait(1600);

                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                Log.v(TAG, "Bond timeout");
                            }
                            // After 1.6s the services are already discovered so the following gatt.discoverServices() finishes almost immediately.
                            // NOTE: This also works with shorted waiting time. The gatt.discoverServices() must be called after the indication is received which is
                            // about 600ms after establishing connection. Values 600 - 1600ms should be OK.
                        }
                        gatt.discoverServices();

                        if (!success) {
                            Log.e(TAG,"ERROR_SERVICE_DISCOVERY_NOT_STARTED");
                            mConnectionState = STATE_ERROR;
                        } else {
                            // Wait for service discovery to clear lock
                            return;
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        intentAction = ACTION_GATT_DISCONNECTED;
                        mConnectionState = STATE_DISCONNECTED;
                        Log.i(TAG, "Disconnected from GATT server.");
                    }

                    // Clear any locks
                    synchronized (lock) {
                        lock.notifyAll();
                    }

                }

                @Override
                // New services discovered
                public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "onServicesDiscovered SUCCESS");

                        Log.v(TAG, String.valueOf(gatt.getServices()));
                    } else {
                        Log.w(TAG, "onServicesDiscovered received: " + status);
                        mConnectionState = STATE_ERROR;
                    }

                    // Clear locks
                    mConnectionState = STATE_CONNECTED_AND_READY;
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    Log.v(TAG, "onServicesDiscovered :: Cleared locks");
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
                        Log.v(TAG, "GATT WRITE ERROR. status:" + Integer.toString(status));
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
                            if (notificationValue[1] == REGION_DAL && python == false)
                                dalHash = bytesToHex(hash);

                            if (notificationValue[1] == REGION_DAL && python == true)
                                dalHash = bytesToHex(hash);

                            synchronized (region_lock) {
                                region_lock.notifyAll();
                            }

                            break;
                        }
                        case FLASH_COMMAND: {
                            packetState = notificationValue[1];
                            synchronized (lock) {
                                lock.notifyAll();
                            }
                        }
                    }
                }

                @Override
                public void onDescriptorWrite (BluetoothGatt gatt,
                                        BluetoothGattDescriptor descriptor,
                                        int status){
                    Log.v(TAG, "onDescriptorWrite :: " + status);

                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        Log.v(TAG, "Descriptor success");
                        Log.v(TAG, "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);
                        descriptorWriteSuccess = true;
                    } else {
                        Log.v(TAG, "onDescriptorWrite: " + status);
                    }

                    synchronized (lock) {
                        lock.notifyAll();
                        Log.v(TAG, "onDescriptorWrite :: clear locks");
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
    public Boolean writePartialFlash(BluetoothGattCharacteristic partialFlashCharacteristic, byte[] data){
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

        sendProgressBroadcastStart();

        try {

            Log.v(TAG, "attemptPartialFlash()");
            Log.v(TAG, filePath);
            HexUtils hex = new HexUtils(filePath);
            Log.v(TAG, "searchForData()");
            int magicIndex = hex.searchForData(PXT_MAGIC);

            if (magicIndex == -1) {
                magicIndex = hex.searchForDataRegEx(UPY_MAGIC);
                python = true;
            }

            Log.v(TAG, "/searchForData() = " + magicIndex);
            if (magicIndex > -1) {
                
                Log.v(TAG, "Found PXT_MAGIC");

                // Get Memory Map from Microbit
                try {
                    Log.v(TAG, "readMemoryMap()");
                    readMemoryMap();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                
                // Find DAL hash
                if(python) magicIndex = magicIndex - 3;

                int record_length = hex.getRecordDataLengthFromIndex(magicIndex );
                Log.v(TAG, "Length of record: " + record_length);

                int magic_offset = (record_length == 64) ? 32 : 0;
                String hashes = hex.getDataFromIndex(magicIndex + ((record_length == 64) ? 0 : 1)); // Size of rows
                int chunks_per_line = magic_offset / 16;

                Log.v(TAG, hashes);

                if(hashes.charAt(3) == '2') {
                    // Uses a hash pointer. Create regex and extract from hex
                    String regEx = ".*" +
                            dalHash +
                            ".*";
                    int hashIndex = hex.searchForDataRegEx(regEx);

                    // TODO Uses CRC of hash
                    if(hashIndex == -1 ) {
                        // return PF_ATTEMPT_DFU;
                    }
                    // hashes = hex.getDataFromIndex(hashIndex);
                    // Log.v(TAG, "hash: " + hashes);
                } else if(!hashes.substring(magic_offset, magic_offset + 16).equals(dalHash)) {
                        Log.v(TAG, hashes.substring(magic_offset, magic_offset + 16) + " " + (dalHash));
                        return PF_ATTEMPT_DFU;
                }

                numOfLines = hex.numOfLines() - magicIndex;
                Log.v(TAG, "Total lines: " + numOfLines);

                // Ready to flash!
                // Loop through data
                String hexData;
                int packetNum = 0;
                int lineCount = 0;

                Log.v(TAG, "enter flashing loop");

                while(true){
                    // Timeout if total is > 30 seconds
                    if(SystemClock.elapsedRealtime() - startTime > 60000) {
                        Log.v(TAG, "Partial flashing has timed out");
                        return PF_FAILED;
                    }

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
                    boolean writeStatus = writePartialFlash(partialFlashCharacteristic, chunk);

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
                                lock.wait(5000);
                            }

                            // Timeout if longer than 5 seconds
                            if((SystemClock.elapsedRealtime() - timeout) > 5000)return PF_FAILED;
                        }

                        packetState = PACKET_STATE_WAITING;

                        Log.v(TAG, "/Wait for notification");

                    } else {
                        Thread.sleep(5);
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
                writePartialFlash(partialFlashCharacteristic, endOfFlashPacket);

                // Finished Writing
                Log.v(TAG, "Flash Complete");
                packetState = PACKET_STATE_COMPLETE_FLASH;
                sendProgressBroadcast(100);
                sendProgressBroadcastComplete();

                // Time execution
                long endTime = SystemClock.elapsedRealtime();
                long elapsedMilliSeconds = endTime - startTime;
                double elapsedSeconds = elapsedMilliSeconds / 1000.0;
                Log.v(TAG, "Flash Time: " + Float.toString((float)elapsedSeconds) + " seconds");

            } else {
                return PF_ATTEMPT_DFU;
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }


        return PF_SUCCESS;
    }

    protected BluetoothGatt connect(@NonNull final String address) {
        if (!mBluetoothAdapter.isEnabled())
            return null;

        mConnectionState = STATE_CONNECTING;

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        BluetoothGatt gatt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gatt = device.connectGatt(this, false, mGattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_2M_MASK);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(this, false, mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(this, false, mGattCallback);
        }

        // We have to wait until the device is connected and services are discovered
        // Connection error may occur as well.
        try {
            synchronized (lock) {
                while ((mConnectionState == STATE_CONNECTING || mConnectionState == STATE_CONNECTED))
                    lock.wait();
            }
        } catch (final InterruptedException e) {
        }

        Log.v(TAG, "return gatt");

        return gatt;
    }

    /**
     * Initializes bluetooth adapter
     *
     * @return <code>true</code> if initialization was successful
     */
    private boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            return false;
        }

        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            return false;
        }

        return true;
    }

    public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";
    public static final String BROADCAST_PF_ATTEMPT_DFU = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ATTEMPT_DFU";

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        Log.v(TAG, "Start Partial Flash");

        final String filePath = intent.getStringExtra("filepath");
        final String deviceAddress = intent.getStringExtra("deviceAddress");
        final int hardwareType = intent.getIntExtra("hardwareType", 1);
        final boolean pf = intent.getBooleanExtra("pf", true);

        mBluetoothGatt = connect(deviceAddress);

        Log.v(TAG, mBluetoothGatt.toString());

        if (mBluetoothGatt == null) {
            final Intent broadcast = new Intent(DFU_BROADCAST_ERROR);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        }

        // Check for partial flashing
        pfService = mBluetoothGatt.getService(PARTIAL_FLASHING_SERVICE);

        // Check partial flashing service exists
        if(pfService == null) {
            Log.v(TAG, "Partial Flashing Service == null");
            final Intent broadcast = new Intent(BROADCAST_PF_ATTEMPT_DFU);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        }

        // Check for characteristic
        partialFlashCharacteristic = pfService.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC);

        if(partialFlashCharacteristic == null) {
            final Intent broadcast = new Intent(BROADCAST_PF_ATTEMPT_DFU);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        }

        // Set up notifications
        boolean res;
        res = mBluetoothGatt.setCharacteristicNotification(partialFlashCharacteristic, true);
        Log.v(TAG, "Set notifications: " + res);

        BluetoothGattDescriptor descriptor = partialFlashCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        res = mBluetoothGatt.writeDescriptor(descriptor);
        Log.v(TAG,"writeDescriptor: " + res);

        // We have to wait until device receives a response or an error occur
        try {
            synchronized (lock) {
                    lock.wait();
                     Log.v(TAG, "Descriptor value: " + descriptor.getValue());
            }
        } catch (final InterruptedException e) {
        }

        /*
        if(false) {
            // Partial flashing started but failed. Need to PF or USB flash to fix
            final Intent broadcast = new Intent(BROADCAST_PF_FAILED);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        }
        */

        int pf_result = PF_ATTEMPT_DFU;
        if(pf) {
            pf_result = attemptPartialFlash(filePath);
        }
        if(pf_result == PF_ATTEMPT_DFU)
        {

            Log.v(TAG, "Partial Flashing not possible");

            // If v1 we need to switch the DFU mode
            if(hardwareType == MICROBIT_V1) {
                BluetoothGattService dfuService = mBluetoothGatt.getService(MICROBIT_DFU_SERVICE);
                // Write Characteristic to enter DFU mode
                if (dfuService == null) {
                    Log.v(TAG, "DFU Service is null");
                    final Intent broadcast = new Intent(DFU_BROADCAST_ERROR);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
                    return;
                }
                BluetoothGattCharacteristic microbitDFUCharacteristic = dfuService.getCharacteristic(MICROBIT_DFU_CHARACTERISTIC);
                byte payload[] = {0x01};
                if (microbitDFUCharacteristic == null) {
                    final Intent broadcast = new Intent(DFU_BROADCAST_ERROR);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
                    return;
                }


                microbitDFUCharacteristic.setValue(payload);
                boolean status = mBluetoothGatt.writeCharacteristic(microbitDFUCharacteristic);
                Log.v(TAG, "MicroBitDFU :: Enter DFU Result " + status);

                synchronized (lock) {
                    try {
                        lock.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

            mBluetoothGatt.disconnect();

            Log.v(TAG, "Send Intent: BROADCAST_PF_ATTEMPT_DFU");
            final Intent broadcast = new Intent(BROADCAST_PF_ATTEMPT_DFU);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        } else if(pf_result == PF_FAILED) {
            // Partial flashing started but failed. Need to PF or USB flash to fix
            final Intent broadcast = new Intent(BROADCAST_PF_FAILED);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        }

        // Check complete before leaving intent
        if(packetState != PACKET_STATE_COMPLETE_FLASH) {
            final Intent broadcast = new Intent(BROADCAST_PF_FAILED);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        }

        Log.v(TAG, "onHandleIntent End");
    }

    /*
    Read Memory Map from the MB
     */
    private Boolean readMemoryMap() throws InterruptedException {
        boolean status; // Gatt Status

        try {
            for (int i = 0; i < 3; i++)
            {
                // Get Start, End, and Hash of each Region
                // Request Region
                byte[] payload = {REGION_INFO_COMMAND, (byte)i};
                if(partialFlashCharacteristic == null || mBluetoothGatt == null) return false;
                partialFlashCharacteristic.setValue(payload);
                status = mBluetoothGatt.writeCharacteristic(partialFlashCharacteristic);
                if(!status) {
                    Log.v(TAG, "Failed to write to Region characteristic");
                    return false;
                }
                Log.v(TAG, "Request Region " + i);

                synchronized (region_lock) {
                    region_lock.wait(2000);
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

