package org.microbit.android.partialflashing;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Arrays;
import java.util.UUID;

import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.callback.DataReceivedCallback;
import no.nordicsemi.android.ble.data.Data;

import static org.microbit.android.partialflashing.PartialFlashingBaseService.BROADCAST_PF_ATTEMPT_DFU;

public class PartialFlashingBLEManager extends BleManager {
    public static final UUID PARTIAL_FLASHING_SERVICE = UUID.fromString("e97dd91d-251d-470a-a062-fa1922dfa9a8");
    public static final UUID PARTIAL_FLASH_CHARACTERISTIC = UUID.fromString("e97d3b10-251d-470a-a062-fa1922dfa9a8");

    private static final UUID MICROBIT_DFU_SERVICE = UUID.fromString("e95d93b0-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_SECURE_DFU_SERVICE = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb");
    private static final UUID MICROBIT_DFU_CHARACTERISTIC = UUID.fromString("e95d93b1-251d-470a-a062-fa1922dfa9a8");
    private static final UUID MICROBIT_SECURE_DFU_CHARACTERISTIC = UUID.fromString("8ec90004-f315-4f60-9fb8-838830daea50");

    private static final String TAG = PartialFlashingBLEManager.class.getSimpleName();

    private static final byte PACKET_STATE_WAITING = 0;
    private static final byte PACKET_STATE_SENT = (byte)0xFF;
    private static final byte PACKET_STATE_RETRANSMIT = (byte)0xAA;
    private static final byte PACKET_STATE_COMPLETE_FLASH = (byte) 0xCF;

    public static byte packetState = PACKET_STATE_WAITING;

    // Used to lock the program state while we wait for a Bluetooth operation to complete
    private static final boolean BLE_WAITING = false;
    private static final boolean BLE_READY = true;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final int STATE_CONNECTED_AND_READY = 3;
    private static final int STATE_ERROR = 4;

    // Regions
    private static final int REGION_SD = 0;
    private static final int REGION_DAL = 1;
    private static final int REGION_MAKECODE = 2;

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

    // Partial Flashing Intent Action Values
    private static final int PF_SUCCESS = 0x0;
    private static final int PF_ATTEMPT_DFU = 0x1;
    private static final int PF_FAILED = 0x2;
    private static final int PF_START = 0x3;

    static String dalHash;

    // Client characteristics
    private BluetoothGattCharacteristic partialFlashingCharacteristic;

    private BluetoothGattCharacteristic v1DFUCharacteristic;
    private BluetoothGattCharacteristic v2DFUCharacteristic;
    private boolean fullDfuFlag = false;

    static final Object mObject = new Object();

    PartialFlashingBLEManager(@NonNull final Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected BleManagerGattCallback getGattCallback() {
        return new PartialFlashingBLEManagerGattCallback();
    }

    @Override
    public void log(final int priority, @NonNull final String message) {
        Log.v(TAG,  message);
    }

    static public String getDalHash() {
        return dalHash;
    }

    /**
     * BluetoothGatt callbacks object.
     */
    private class PartialFlashingBLEManagerGattCallback extends BleManagerGattCallback {

        @Override
        protected boolean isRequiredServiceSupported(@NonNull final BluetoothGatt gatt) {
            // Init service check
            Log.v(TAG, "isRequiredServiceSupported");
            dalHash = null;

            final BluetoothGattService service = gatt.getService(PARTIAL_FLASHING_SERVICE);
            if (service != null) {
                partialFlashingCharacteristic = service.getCharacteristic(PARTIAL_FLASH_CHARACTERISTIC);
                partialFlashingCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }

            final BluetoothGattService v1DFU = gatt.getService(MICROBIT_DFU_SERVICE);
            if(v1DFU != null) {
                v1DFUCharacteristic = v1DFU.getCharacteristic(MICROBIT_DFU_CHARACTERISTIC);
                v1DFUCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }

            final BluetoothGattService v2DFU = gatt.getService(MICROBIT_SECURE_DFU_SERVICE);
            if(v2DFU != null) {
                v2DFUCharacteristic = v2DFU.getCharacteristic(MICROBIT_SECURE_DFU_CHARACTERISTIC);
                v2DFUCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            }

            // Validate properties
            boolean notify = false;
            boolean write = false;

            if (partialFlashingCharacteristic != null) {
                final int properties = partialFlashingCharacteristic.getProperties();
                notify = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                write = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
            }

            // Return true if all required services have been found
            Log.v(TAG, "PF Supported: " + ((partialFlashingCharacteristic == null) ? "Service NULL" : "Service Exists;") +
                    " Notify: " + notify +
                    " WriteNoResponse " + write);

            return partialFlashingCharacteristic != null && notify && write;
        }

        // If you have any optional services, allocate them here. Return true only if
        // they are found.
        @Override
        protected boolean isOptionalServiceSupported(@NonNull final BluetoothGatt gatt) {
            return super.isOptionalServiceSupported(gatt);
        }

        // Initialize your device here. Often you need to enable notifications and set required
        // MTU or write some initial data. Do it here.
        @Override
        public void initialize() {
            Log.v(TAG, "Initialize");
            fullDfuFlag = false;

            enableNotifications(partialFlashingCharacteristic).enqueue();

            if(v1DFUCharacteristic != null) {
                enableIndications(v1DFUCharacteristic).enqueue();
                enableNotifications(v1DFUCharacteristic).enqueue();
            } else if(v2DFUCharacteristic != null) {
                // enableIndications(v2DFUCharacteristic).enqueue();
                // enableNotifications(v2DFUCharacteristic).enqueue();
            } else { // No DFU services?
                refreshDeviceCache().enqueue();
            }

            setNotificationCallback(partialFlashingCharacteristic).with(new DataReceivedCallback() {
                private static final byte REGION_INFO_COMMAND = 0x0;
                private static final byte FLASH_COMMAND = 0x1;

                private static final int REGION_SD = 0;
                private static final int REGION_DAL = 1;
                private static final int REGION_MAKECODE = 2;

                @Override
                public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
                    byte notificationValue[] = data.getValue();
                    Log.v(TAG, "Received Notification: " + bytesToHex(notificationValue));

                    // What command
                    switch (notificationValue[0]) {
                        case REGION_INFO_COMMAND: {
                            // Get Hash + Start / End addresses
                            Log.v(TAG, "Region: " + notificationValue[1]);

                            byte[] startAddress = Arrays.copyOfRange(notificationValue, 2, 6);
                            byte[] endAddress = Arrays.copyOfRange(notificationValue, 6, 10);
                            Log.v(TAG, "startAddress: " + bytesToHex(startAddress) + " endAddress: " + bytesToHex(endAddress));

                            byte[] hash = Arrays.copyOfRange(notificationValue, 10, 18);
                            Log.v(TAG, "Hash: " + bytesToHex(hash));

                            if (notificationValue[1] == REGION_DAL) {
                                Log.v(TAG, "Set DAL hash");
                                PartialFlashingBLEManager.dalHash = bytesToHex(hash);
                                synchronized (PartialFlashingBaseService.mObject) {
                                    PartialFlashingBaseService.mObject.notifyAll();
                                }
                            }
                            break;
                        }
                        case FLASH_COMMAND: {
                            PartialFlashingBLEManager.packetState = notificationValue[1];
                        }
                        break;
                        default:
                            throw new IllegalStateException("Unexpected value: " + notificationValue[0]);
                    }
                }

            });

            // Get hash when connecting
            byte[] payload = {0x00, (byte) 0x01};
            writeCharacteristic(partialFlashingCharacteristic, payload).enqueue();

        }

        @Override
        protected void onDeviceDisconnected() {
            Log.v(TAG, "onDeviceDisconnected");

            // Device disconnected. Release your references here.
            partialFlashingCharacteristic = null;
            v1DFUCharacteristic = null;
            v2DFUCharacteristic = null;

            if(fullDfuFlag) {
                Intent intent = new Intent(BROADCAST_PF_ATTEMPT_DFU);
                LocalBroadcastManager.getInstance(getContext()).sendBroadcast(intent);

                fullDfuFlag = false;
            }
        }
    }
    
    public void enterDFU() {

        Log.v(TAG, "EnterDFU");
        fullDfuFlag = true;

        if(v1DFUCharacteristic != null) {
            writeCharacteristic(v1DFUCharacteristic, new byte[]{0x01}).enqueue();
            Log.v(TAG, "Sending DFU request");
        } else if(v2DFUCharacteristic != null) {
            // Should be handled by DFU Lib
            writeCharacteristic(v2DFUCharacteristic, new byte[]{0x01}).enqueue();
            Log.v(TAG, "Sending DFU request");
        } else {
            refreshDeviceCache().enqueue();
        }

        synchronized (mObject) {
            try {
                mObject.wait(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    public void writePartialFlash(byte[] data) {
        Log.v(TAG, "writePartialFlash");
        writeCharacteristic(partialFlashingCharacteristic, data).enqueue();
    }

    public static String bytesToHex(byte[] bytes) {
        final char[] hexArray = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for (int j = 0; j < bytes.length; j++) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
}