package org.microbit.android.partialflashing;

import android.annotation.SuppressLint;
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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
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
    private final static String TAG = PartialFlashingBaseService.class.getSimpleName();
    private static boolean DEBUG = false;
    public void logi(String message) {
        if( DEBUG) {
            Log.i(TAG, "### " + Thread.currentThread().getId() + " # " + message);
        }
    }

    // ================================================================
    // INTENT SERVICE

    public static final String DFU_BROADCAST_ERROR = "no.nordicsemi.android.dfu.broadcast.BROADCAST_ERROR";
    public static final String BROADCAST_ACTION = "org.microbit.android.partialflashing.broadcast.BROADCAST_ACTION";
    public static final String BROADCAST_PROGRESS = "org.microbit.android.partialflashing.broadcast.BROADCAST_PROGRESS";
    public static final String BROADCAST_START = "org.microbit.android.partialflashing.broadcast.BROADCAST_START";
    public static final String BROADCAST_COMPLETE = "org.microbit.android.partialflashing.broadcast.BROADCAST_COMPLETE";
    public static final String EXTRA_PROGRESS = "org.microbit.android.partialflashing.extra.EXTRA_PROGRESS";
    public static final String BROADCAST_PF_FAILED = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_FAILED";
    public static final String BROADCAST_PF_ATTEMPT_DFU = "org.microbit.android.partialflashing.broadcast.BROADCAST_PF_ATTEMPT_DFU";

    protected abstract Class<? extends Activity> getNotificationTarget();

    public PartialFlashingBaseService() {
        super(TAG);
    }

    /* Receive updates on user interaction */
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, final Intent intent) {
            logi( "Received Broadcast: " + intent.toString());
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        DEBUG = isDebug();

        logi( "onCreate");

        // Create intent filter and add to Local Broadcast Manager so that we can use an Intent to
        // start the Partial Flashing Service

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PartialFlashingBaseService.BROADCAST_ACTION);

        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.registerReceiver(broadcastReceiver, intentFilter);

        initialize();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logi( "onDestroy");
        final LocalBroadcastManager manager = LocalBroadcastManager.getInstance(this);
        manager.unregisterReceiver(broadcastReceiver);
    }

    private void sendProgressBroadcast(final int progress) {

        logi( "Sending progress broadcast: " + progress + "%");

        final Intent broadcast = new Intent(BROADCAST_PROGRESS);
        broadcast.putExtra(EXTRA_PROGRESS, progress);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastStart() {

        logi( "Sending progress broadcast start");

        final Intent broadcast = new Intent(BROADCAST_START);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    private void sendProgressBroadcastComplete() {

        logi( "Sending progress broadcast complete");

        final Intent broadcast = new Intent(BROADCAST_COMPLETE);

        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        logi("onHandleIntent");

        final String filePath = intent.getStringExtra("filepath");
        final String deviceAddress = intent.getStringExtra("deviceAddress");
        final int hardwareType = intent.getIntExtra("hardwareType", 1);
        final boolean pf = intent.getBooleanExtra("pf", true);

        partialFlash( filePath, deviceAddress, pf);
        logi("onHandleIntent END");
    }

    /**
     * Initializes bluetooth adapter
     *
     * @return <code>true</code> if initialization was successful
     */
    private boolean initialize() {
        logi( "initialize");
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

    protected boolean isDebug() {
        return false;
    }

    // ================================================================
    // PARTIAL FLASH

    public static final UUID PARTIAL_FLASH_CHARACTERISTIC = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8");
    public static final UUID PARTIAL_FLASHING_SERVICE = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8");
    public static final String PXT_MAGIC = "708E3B92C615A841C49866C975EE5197";
    public static final String UPY_MAGIC = ".*FE307F59.{16}9DD7B1C1.*";

    private static final UUID NORDIC_DFU_SERVICE = UUID.fromString("00001530-1212-EFDE-1523-785FEABCD123");
    private static final UUID MICROBIT_DFU_SERVICE = UUID.fromString("e95d93b0-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_SECURE_DFU_SERVICE = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb");
    private static final UUID MICROBIT_DFU_CHARACTERISTIC = UUID.fromString("e95d93b1-251d-470a-a062-fa1922dfa9a8");

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt = null;
    private int mConnectionState = STATE_DISCONNECTED;
    private BluetoothDevice device;
    private boolean descriptorWriteSuccess = false;
    BluetoothGattDescriptor descriptorRead = null;
    boolean descriptorReadSuccess = false;
    byte[] descriptorValue = null;

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

    private static final UUID GENERIC_ATTRIBUTE_SERVICE_UUID = UUID.fromString("00001801-0000-1000-8000-00805F9B34FB");
    private static final UUID SERVICE_CHANGED_UUID           = UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Regions
    private static final int REGION_SD = 0;
    private static final int REGION_DAL = 1;
    private static final int REGION_MAKECODE = 2;

    // DAL Hash
    boolean python = false;
    String dalHash;

    long code_startAddress = 0;

    long code_endAddress = 0;

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


    // Various callback methods defined by the BLE API.
    private final BluetoothGattCallback mGattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt gatt, int status,
                                                    int newState) {
                    logi( "onConnectionStateChange " + newState + " status " + status);
                    //TODO this ignores status

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        logi( "STATE_CONNECTED");

                        mConnectionState = STATE_CONNECTED;

                        /* Taken from Nordic. See reasoning here: https://github.com/NordicSemiconductor/Android-DFU-Library/blob/e0ab213a369982ae9cf452b55783ba0bdc5a7916/dfu/src/main/java/no/nordicsemi/android/dfu/DfuBaseService.java#L888 */
                        if (gatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                            logi( "Wait for service changed");
                            timeoutOnLock(1600);
                            logi( "Bond timeout");
                             // NOTE: This also works with shorter waiting time. The gatt.discoverServices() must be called after the indication is received which is
                            // about 600ms after establishing connection. Values 600 - 1600ms should be OK.
                        }
                        final boolean success = gatt.discoverServices();

                        if (!success) {
                            Log.e(TAG,"ERROR_SERVICE_DISCOVERY_NOT_STARTED");
                            mConnectionState = STATE_ERROR;
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
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
                        logi( String.valueOf(gatt.getServices()));
                        mConnectionState = STATE_CONNECTED_AND_READY;
                    } else {
                        Log.w(TAG, "onServicesDiscovered received: " + status);
                        mConnectionState = STATE_ERROR;
                    }

                    // Clear locks
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    logi( "onServicesDiscovered :: Cleared locks");
                }
                @Override
                // API 31 Android 12
                public void onServiceChanged (BluetoothGatt gatt) {
                    super.onServiceChanged( gatt);
                    logi( "onServiceChanged");
                }
                @Override
                // Result of a characteristic read operation
                public void onCharacteristicRead(BluetoothGatt gatt,
                                                 BluetoothGattCharacteristic characteristic,
                                                 int status) {
                    logi( String.valueOf(status));
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
                        logi( "GATT status: Success");
                    } else {
                        // TODO Attempt to resend?
                        logi( "GATT WRITE ERROR. status:" + Integer.toString(status));
                    }
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                    super.onCharacteristicChanged(gatt, characteristic);
                    byte notificationValue[] = characteristic.getValue();
                    logi( "Received Notification: " + bytesToHex(notificationValue));

                    // What command
                    switch(notificationValue[0])
                    {
                        case REGION_INFO_COMMAND: {
                            // Get Hash + Start / End addresses
                            logi( "Region: " + notificationValue[1]);

                            byte[] startAddress = Arrays.copyOfRange(notificationValue, 2, 6);
                            byte[] endAddress = Arrays.copyOfRange(notificationValue, 6, 10);
                            logi( "startAddress: " + bytesToHex(startAddress) + " endAddress: " + bytesToHex(endAddress));

                            if ( notificationValue[1] == REGION_MAKECODE) {
                                code_startAddress = Byte.toUnsignedLong(notificationValue[5])
                                        + Byte.toUnsignedLong(notificationValue[4]) * 256
                                        + Byte.toUnsignedLong(notificationValue[3]) * 256 * 256
                                        + Byte.toUnsignedLong(notificationValue[2]) * 256 * 256 * 256;

                                code_endAddress = Byte.toUnsignedLong(notificationValue[9])
                                        + Byte.toUnsignedLong(notificationValue[8]) * 256
                                        + Byte.toUnsignedLong(notificationValue[7]) * 256 * 256
                                        + Byte.toUnsignedLong(notificationValue[6]) * 256 * 256 * 256;
                            }

                            byte[] hash = Arrays.copyOfRange(notificationValue, 10, 18);
                            logi( "Hash: " + bytesToHex(hash));

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
                public void onDescriptorRead (BluetoothGatt gatt,
                                              BluetoothGattDescriptor descriptor,
                                              int status,
                                              byte[] value) {
                    logi( "onDescriptorRead :: " + status);

                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        logi( "Descriptor read success");
                        logi( "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);
                        descriptorReadSuccess = true;
                        descriptorRead = descriptor;
                        descriptorValue = value;
                    } else {
                        logi( "onDescriptorRead: " + status);
                    }

                    synchronized (lock) {
                        lock.notifyAll();
                        logi( "onDescriptorWrite :: clear locks");
                    }

                }

                @Override
                public void onDescriptorWrite (BluetoothGatt gatt,
                                        BluetoothGattDescriptor descriptor,
                                        int status){
                    logi( "onDescriptorWrite :: " + status);

                    if(status == BluetoothGatt.GATT_SUCCESS) {
                        logi( "Descriptor success");
                        logi( "GATT: " + gatt.toString() + ", Desc: " + descriptor.toString() + ", Status: " + status);
                        descriptorWriteSuccess = true;
                    } else {
                        logi( "onDescriptorWrite: " + status);
                    }

                    synchronized (lock) {
                        lock.notifyAll();
                        logi( "onDescriptorWrite :: clear locks");
                    }

                }

            };


    // Write to BLE Flash Characteristic
    public Boolean writePartialFlash(BluetoothGattCharacteristic partialFlashCharacteristic, byte[] data){
        partialFlashCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        partialFlashCharacteristic.setValue(data);
        boolean status = mBluetoothGatt.writeCharacteristic(partialFlashCharacteristic);
        return status;
    }

    public int attemptPartialFlash(String filePath) {
        logi( "Flashing: " + filePath);

        sendProgressBroadcastStart();

        try {

            logi( "attemptPartialFlash()");
            logi( filePath);
            HexUtils hex = new HexUtils(filePath);
            logi( "searchForData()");
            String hash = "";
            int magicPart  = 0;
            int magicIndex = hex.searchForData(PXT_MAGIC);
            if (magicIndex > -1) {
                python = false;
                String magicData = hex.getDataFromIndex(magicIndex);
                magicPart = magicData.indexOf(PXT_MAGIC);
                int hashStart = magicPart + PXT_MAGIC.length();
                int hashNext = hashStart + 16;
                if ( hashStart < magicData.length()) {
                    if ( hashNext < magicData.length())
                        hash = magicData.substring( hashStart, hashNext);
                    else
                        hash = magicData.substring( hashStart);
                }
                if ( hash.length() < 16) {
                    String nextData = hex.getDataFromIndex( magicIndex + 1);
                    hash = hash + nextData.substring( 0, 16 - hash.length());
                }
            } else {
//                magicIndex = hex.searchForDataRegEx(UPY_MAGIC);
//                python = true;
            }

            if (magicIndex == -1) {
                logi( "No magic");
                return PF_ATTEMPT_DFU;
            }

            logi( "Found PXT_MAGIC at " + magicIndex + " at offset " + magicPart);

            // Get Memory Map from Microbit
            code_startAddress = code_endAddress = 0;
            if ( !readMemoryMap())
            {
                Log.w(TAG, "Failed to read memory map");
                return PF_ATTEMPT_DFU;
            }
            // TODO: readMemoryMap may still have failed - more checks are needed

            // Compare DAL hash
            if( !python) {
                if ( code_startAddress == 0 || code_endAddress <= code_startAddress)
                {
                    logi( "Failed to read memory map code address");
                    return PF_ATTEMPT_DFU;
                }
                if (!hash.equals(dalHash)) {
                    logi( hash + " " + (dalHash));
                    return PF_ATTEMPT_DFU;
                }
            } else {
                magicIndex = magicIndex - 3;

                int record_length = hex.getRecordDataLengthFromIndex(magicIndex);
                logi( "Length of record: " + record_length);

                int magic_offset = (record_length == 64) ? 32 : 0;
                String hashes = hex.getDataFromIndex(magicIndex + ((record_length == 64) ? 0 : 1)); // Size of rows
                int chunks_per_line = magic_offset / 16;

                logi( hashes);

                if (hashes.charAt(3) == '2') {
                    // Uses a hash pointer. Create regex and extract from hex
                    String regEx = ".*" +
                            dalHash +
                            ".*";
                    int hashIndex = hex.searchForDataRegEx(regEx);

                    // TODO Uses CRC of hash
                    if (hashIndex == -1) {
                        // return PF_ATTEMPT_DFU;
                    }
                    // hashes = hex.getDataFromIndex(hashIndex);
                    // logi( "hash: " + hashes);
                } else if (!hashes.substring(magic_offset, magic_offset + 16).equals(dalHash)) {
                    logi( hashes.substring(magic_offset, magic_offset + 16) + " " + (dalHash));
                    return PF_ATTEMPT_DFU;
                }
            }

            int count = 0;
            int numOfLines = hex.numOfLines() - magicIndex;
            logi( "Total lines: " + numOfLines);

            int  magicLo = hex.getRecordAddressFromIndex(magicIndex);
            int  magicHi = hex.getSegmentAddress(magicIndex);
            long magicA   = (long) magicLo + (long) magicHi * 256 * 256 + magicPart;

            int packetNum = 0;
            int lineCount = 0;
            int part = magicPart; // magic is first data to be copied
            int line0 = lineCount;
            int part0 = part;

            int  addrLo = hex.getRecordAddressFromIndex(magicIndex + lineCount);
            int  addrHi = hex.getSegmentAddress(magicIndex + lineCount);
            long addr   = (long) addrLo + (long) addrHi * 256 * 256;

            String hexData;
            String partData;

            Log.w(TAG, "Code start " + code_startAddress + " end " + code_endAddress);
            Log.w(TAG, "First line " + addr);

            // Ready to flash!
            // Loop through data
            logi( "enter flashing loop");

            long addr0 = addr + part / 2;  // two hex digits per byte
            int  addr0Lo = (int) ( addr0 % (256 * 256));
            int  addr0Hi = (int) ( addr0 / (256 * 256));

            if ( code_startAddress != addr0) {
                logi( "Code start address doesn't match");
                return PF_ATTEMPT_DFU;
            }

            long startTime = SystemClock.elapsedRealtime();
            while (true) {
                // Timeout if total is > 30 seconds
                if(SystemClock.elapsedRealtime() - startTime > 60000) {
                    logi( "Partial flashing has timed out");
                    return PF_FAILED;
                }

                // Check if EOF
                if(hex.getRecordTypeFromIndex(magicIndex + lineCount) != 0) {
                    break;
                }

                addrLo = hex.getRecordAddressFromIndex(magicIndex + lineCount);
                addrHi = hex.getSegmentAddress(magicIndex + lineCount);
                addr   = (long) addrLo + (long) addrHi * 256 * 256;

                hexData = hex.getDataFromIndex( magicIndex + lineCount);
                if ( part + 32 > hexData.length()) {
                    partData = hexData.substring( part);
                } else {
                    partData = hexData.substring(part, part + 32);
                }

                int offsetToSend = 0;
                if ( count == 0)
                {
                    line0 = lineCount;
                    part0 = part;
                    addr0 = addr + part / 2;  // two hex digits per byte
                    addr0Lo = (int) ( addr0 % (256 * 256));
                    addr0Hi = (int) ( addr0 / (256 * 256));
                    offsetToSend = addr0Lo;
                } else if (count == 1) {
                    offsetToSend = addr0Hi;
                }

                logi( packetNum + " " + count + " addr0 " + addr0 + " offsetToSend " + offsetToSend + " line " + lineCount + " addr " + addr + " part " + part + " data " + partData);

                // recordToByteArray() builds a PF command block with the data
                byte chunk[] = HexUtils.recordToByteArray(partData, offsetToSend, packetNum);

                // Write without response
                // Wait for previous write to complete
                boolean writeStatus = writePartialFlash(partialFlashCharacteristic, chunk);

                // Sleep after 4 packets
                count++;
                if ( count == 4){
                    count = 0;

                    // Wait for notification
                    logi( "Wait for notification");

                    // Send broadcast while waiting
                    int percent = Math.round((float)100 * ((float)(lineCount) / (float)(numOfLines)));
                    sendProgressBroadcast(percent);

                    long timeout = SystemClock.elapsedRealtime();
                    while(packetState == PACKET_STATE_WAITING) {
                        synchronized (lock) {
                            lock.wait(5000);
                        }

                        // Timeout if longer than 5 seconds
                        if((SystemClock.elapsedRealtime() - timeout) > 5000)
                            return PF_FAILED;
                    }

                    packetState = PACKET_STATE_WAITING;

                    logi( "/Wait for notification");

                } else {
                    Thread.sleep(5);
                }

                // If notification is retransmit -> retransmit last block.
                // Else set start of new block
                if(packetState == PACKET_STATE_RETRANSMIT) {
                    lineCount = line0;
                    part = part0;
                } else {
                    // Next part
                    part = part + partData.length();
                    if ( part >= hexData.length()) {
                        part = 0;
                        lineCount = lineCount + 1;
                    }
                }

                // Always increment packet #
                packetNum = packetNum + 1;
            }

            Thread.sleep(100); // allow time for write to complete

            // Write End of Flash packet
            byte[] endOfFlashPacket = {(byte)0x02};
            boolean writeStatus = writePartialFlash(partialFlashCharacteristic, endOfFlashPacket);

            Thread.sleep(100); // allow time for write to complete

            // Finished Writing
            logi( "Flash Complete");
            packetState = PACKET_STATE_COMPLETE_FLASH;
            sendProgressBroadcast(100);
            sendProgressBroadcastComplete();

            // Time execution
            long endTime = SystemClock.elapsedRealtime();
            long elapsedMilliSeconds = endTime - startTime;
            double elapsedSeconds = elapsedMilliSeconds / 1000.0;
            logi( "Flash Time: " + Float.toString((float)elapsedSeconds) + " seconds");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Check complete before leaving intent
        if (packetState != PACKET_STATE_COMPLETE_FLASH) {
            return PF_FAILED;
        }

        return PF_SUCCESS;
    }

    @SuppressLint("MissingPermission")
    protected BluetoothGatt connect(@NonNull final String address) {
        if (!mBluetoothAdapter.isEnabled())
            return null;

        logi( "connect");

        long start = SystemClock.elapsedRealtime();

        mConnectionState = STATE_CONNECTING;
        int stateWas = mConnectionState;

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        BluetoothGatt gatt;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gatt = device.connectGatt(
                    this,
                    false,
                    mGattCallback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK | BluetoothDevice.PHY_LE_2M_MASK);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(
                    this,
                    false,
                    mGattCallback,
                    BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(
                    this,
                    false,
                    mGattCallback);
        }

        if ( gatt == null) {
            mConnectionState = STATE_ERROR;
            return null;
        }

        // We have to wait until the device is connected and services are discovered
        // Connection error may occur as well.
        try {
            boolean waiting = true;
            while ( waiting && mConnectionState != STATE_CONNECTED_AND_READY) {
                synchronized (lock) {
                    lock.wait(20000);
                }

                String time = Float.toString((float) ( SystemClock.elapsedRealtime() - start) / 1000.0f);
                switch ( mConnectionState) {
                    case STATE_CONNECTED_AND_READY:
                        logi( time + ": STATE_CONNECTED_AND_READY");
                        break;
                    case STATE_ERROR:
                        logi( time + ": STATE_ERROR");
                        break;
                    case STATE_CONNECTED:
                        logi( time + ": STATE_CONNECTED");
                        break;
                    case STATE_CONNECTING:
                        logi( time + ": STATE_CONNECTING");
                        break;
                    case STATE_DISCONNECTED:
                        logi( time + ": STATE_DISCONNECTED");
                        break;
                    default:
                        logi( time + ": " + mConnectionState);
                        break;
                }

                waiting = false;
                switch ( mConnectionState) {
                    case STATE_CONNECTED_AND_READY:
                    case STATE_DISCONNECTED:
                    case STATE_ERROR:
                        break;
                    default:
                        if ( stateWas != mConnectionState)
                            waiting = true;
                        break;
                }
                stateWas = mConnectionState;
            }
        } catch (final InterruptedException e) {
            mConnectionState = STATE_ERROR;
        }

        if ( mConnectionState != STATE_CONNECTED_AND_READY) {
                              gatt.disconnect();
            gatt.close();
            return null;
        }

        logi( "Connected to gatt");
        logi( gatt.toString());
        return gatt;
    }

    private boolean timeoutOnLock( long timeout)
    {
        synchronized (lock) {
            try {
                lock.wait(timeout);
            } catch (final Exception e) {
                e.printStackTrace();
                return false;
            }
        }
        return true;
    }

    private boolean connectedHasV1MicroBitDfu() {
        return mBluetoothGatt.getService( MICROBIT_DFU_SERVICE) != null;
    }

    private boolean connectedHasV1NordicDfu() {
        return mBluetoothGatt.getService( NORDIC_DFU_SERVICE) != null;
    }

    private boolean connectedHasV1Dfu() {
        return connectedHasV1MicroBitDfu() || connectedHasV1NordicDfu();
    }

    private void refreshV1( boolean wantMicroBitDfu) {
        if (connectedHasV1Dfu()) {
            if (wantMicroBitDfu != connectedHasV1MicroBitDfu()) {
                try {
                    final Method refresh = mBluetoothGatt.getClass().getMethod("refresh");
                    refresh.invoke(mBluetoothGatt);
                } catch (final Exception e) {
                }
                mBluetoothGatt.discoverServices();
                timeoutOnLock(2000);
            }
        }
    }

    private void refreshV1ForMicroBitDfu() {
        refreshV1( true);
    }

    private void refreshV1ForNordicDfu() {
        refreshV1( false);
    }

    @SuppressLint("MissingPermission")
    private BluetoothGattCharacteristic serviceChangedCharacteristic() {
        BluetoothGattService gas = mBluetoothGatt.getService(GENERIC_ATTRIBUTE_SERVICE_UUID);
        if (gas == null) {
            return null;
        }
        BluetoothGattCharacteristic scc = gas.getCharacteristic(SERVICE_CHANGED_UUID);
        if (scc == null) {
            return null;
        }
        return scc;
    }

    @SuppressLint("MissingPermission")
    private boolean cccEnabled(BluetoothGattCharacteristic chr, boolean notify) {
        BluetoothGattDescriptor ccc = chr.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (ccc == null) {
            return false;
        }

        descriptorReadSuccess = false;
        mBluetoothGatt.readDescriptor(ccc);
        timeoutOnLock(1000);
        if (!descriptorReadSuccess
                || !descriptorRead.getUuid().equals(CLIENT_CHARACTERISTIC_CONFIG)
                || !descriptorRead.getCharacteristic().getUuid().equals(chr.getUuid())) {
            return false;
        }
        if ( descriptorValue == null || descriptorValue.length != 2) {
            return false;
        }

        boolean enabled = false;
        if ( notify) {
            enabled = descriptorValue[0] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0]
                    && descriptorValue[1] == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[1];
        } else {
            enabled = descriptorValue[0] == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[0]
                    && descriptorValue[1] == BluetoothGattDescriptor.ENABLE_INDICATION_VALUE[1];
        }
        return enabled;
    }

    @SuppressLint("MissingPermission")
    private boolean cccEnable( BluetoothGattCharacteristic chr, boolean notify) {
        BluetoothGattDescriptor ccc = chr.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (ccc == null) {
            return false;
        }

        mBluetoothGatt.setCharacteristicNotification( chr, true);

        byte [] enable = notify
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;

        descriptorWriteSuccess = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mBluetoothGatt.writeDescriptor( ccc, enable);
        } else {
            ccc.setValue(enable);
            mBluetoothGatt.writeDescriptor( ccc);
        }
        timeoutOnLock(1000);
        return descriptorWriteSuccess;
    }

    private void partialFlash( final String filePath, final String deviceAddress, final boolean pf) {
        logi( "partialFlash");

        for ( int i = 0; i < 3; i++) {
            mBluetoothGatt = connect(deviceAddress);
            if (mBluetoothGatt != null)
                break;
        }

        if (mBluetoothGatt == null) {
            logi( "Failed to connect");
            logi( "Send Intent: DFU_BROADCAST_ERROR");
            final Intent broadcast = new Intent(DFU_BROADCAST_ERROR);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
            return;
        }

        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (serviceChangedCharacteristic() != null) {
                if (!cccEnabled(serviceChangedCharacteristic(), false)) {
                    // Only seem to get here with V1
                    // After this, the refresh function is never called
                    // But it doesn't seem to work in Android 8
                    cccEnable(serviceChangedCharacteristic(), false);
                    mBluetoothGatt.disconnect();
                    timeoutOnLock(2000);
                    mBluetoothGatt.close();
                    mBluetoothGatt = null;

                    mBluetoothGatt = connect(deviceAddress);
                    if (mBluetoothGatt == null) {
                        logi( "Failed to connect");
                        logi( "Send Intent: DFU_BROADCAST_ERROR");
                        final Intent broadcast = new Intent(DFU_BROADCAST_ERROR);
                        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
                        return;
                    }
                    if (!cccEnabled(serviceChangedCharacteristic(), false)) {
                        cccEnable(serviceChangedCharacteristic(), false);
                    }
                }
            }
        }

        boolean isV1 = connectedHasV1Dfu();
        if ( isV1) {
            refreshV1ForMicroBitDfu();
        }

        int pfResult = PF_ATTEMPT_DFU;
        if ( pf) {
            logi( "Trying to partial flash");
            if ( partialFlashCharacteristicCheck()) {
                pfResult = attemptPartialFlash( filePath);
            }
        }

        String action = "";

        switch ( pfResult) {
            case PF_FAILED: {
                // Partial flashing started but failed. Need to PF or USB flash to fix
                logi( "Partial flashing failed");
                logi( "Send Intent: BROADCAST_PF_FAILED");
                action = BROADCAST_PF_FAILED;
                break;
            }
            case PF_ATTEMPT_DFU: {
                logi( "Attempt DFU");
                action = BROADCAST_PF_ATTEMPT_DFU;
                // If v1 we need to switch the DFU mode
                if( isV1) {
                    if ( !enterDFUModeV1()) {
                        logi( "Failed to enter DFU mode");
                        action = DFU_BROADCAST_ERROR;
                    }
                }
                break;
            }
            case PF_SUCCESS: {
                logi( "Partial flashing succeeded");
                break;
            }
        }

        logi( "disconnect");
        mBluetoothGatt.disconnect();
        timeoutOnLock( 2000);
        mBluetoothGatt.close();
        mBluetoothGatt = null;

        if ( isV1 && action == BROADCAST_PF_ATTEMPT_DFU) {
            // Try to ensure the NordicDfu profile
            for ( int i = 0; i < 5; i++) {
                mBluetoothGatt = connect(deviceAddress);
                if ( mBluetoothGatt != null)
                    break;
                timeoutOnLock( 1000);
            }

            if ( mBluetoothGatt != null) {
                refreshV1ForNordicDfu();
                mBluetoothGatt.disconnect();
                timeoutOnLock(2000);
                mBluetoothGatt.close();
                mBluetoothGatt = null;
            }
        }

        if ( !action.isEmpty()) {
            logi( "Send Intent: " + action);
            final Intent broadcast = new Intent(action);
            LocalBroadcastManager.getInstance(this).sendBroadcast(broadcast);
        }
        logi( "onHandleIntent End");
    }

    protected boolean partialFlashCharacteristicCheck() {

        // Check for partial flashing
        pfService = mBluetoothGatt.getService(PARTIAL_FLASHING_SERVICE);

        // Check partial flashing service exists
        if (pfService == null) {
            logi( "Partial Flashing Service == null");
            return false;
        }

        // Check for characteristic
        partialFlashCharacteristic = pfService.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC);

        if (partialFlashCharacteristic == null) {
            logi( "Partial Flashing Characteristic == null");
            return false;
        }

        logi( "Enable notifications");
        if ( !cccEnable( partialFlashCharacteristic, true))
        {
            logi( "Enable notifications failed");
            return false;
        }
        return true;
    }

    protected boolean enterDFUModeV1() {
        BluetoothGattService dfuService = mBluetoothGatt.getService(MICROBIT_DFU_SERVICE);
        // Write Characteristic to enter DFU mode
        if (dfuService == null) {
            logi( "DFU Service is null");
            return false;
        }
        BluetoothGattCharacteristic microbitDFUCharacteristic = dfuService.getCharacteristic(MICROBIT_DFU_CHARACTERISTIC);
        if (microbitDFUCharacteristic == null) {
            logi( "DFU Characteristic is null");
            return false;
        }

        byte payload[] = {0x01};
        microbitDFUCharacteristic.setValue(payload);
        boolean status = mBluetoothGatt.writeCharacteristic(microbitDFUCharacteristic);
        logi( "MicroBitDFU :: Enter DFU Result " + status);

        synchronized (lock) {
            try {
                lock.wait(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // assume it succeeded
        return true;
    }

    /*
    Read Memory Map from the MB
     */
    private boolean readMemoryMap() {
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
                    logi( "Failed to write to Region characteristic");
                    return false;
                }
                logi( "Request Region " + i);

                synchronized (region_lock) {
                    region_lock.wait(2000);
                }
            }
        } catch (Exception e){
            Log.e(TAG, e.toString());
            return false;
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
}

