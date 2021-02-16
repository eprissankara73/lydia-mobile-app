package com.epri.testbleproto;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private final static String TAG = MainActivity.class.getSimpleName();
    private final static byte TUART_HW2APP_CURRENT_SETTINGS = 0x01;
    private final static byte TUART_HW2APP_STATUS = 0x03;
    private final static byte TUART_HW2APP_TEMPERATURE_BUF = 0x05;
    private final static byte TUART_HW2APP_RUNTIME_BUF = 0x07;
    private final static byte TUART_HW2APP_SETPOINT_BUF = 0x09;
    private final static byte TUART_APP2HW_TIME_SYNC = 0x02;
    private final static byte TUART_APP2HW_USER_SETTINGS = 0x04;
    private final static byte TUART_APP2HW_SCHEDULES = 0x06;
    private final static byte TUART_APP2HW_STATUS = 0x08;
    private final static int TUART_BUF_LENGTH = 128;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mGatt;
    private boolean mScanning;
    private String devString = "LYDIA00001"; //"LYDIA00000001";
    private BluetoothDevice mBLEDevice = null;

    private static final long CONNECT_GATT_DELAY = 1000;// 500; //msec
    private BluetoothGattService thermostatService;
    private BluetoothGattService tUartService;
    private BluetoothGattCharacteristic tuart_rx_charac;
    private BluetoothGattCharacteristic tuart_tx_charac;

    private byte[] schedule_arr = new byte[25 * 7 + 5];
    private int schedule_index = 0;
    private String proto_buf_str = "";

    private TextView textViewDevice;

    private boolean receiver_registered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        android.support.v7.widget.Toolbar myToolbar = (android.support.v7.widget.Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);

        //android.support.v7.app.ActionBar ab = getSupportActionBar();
        //ab.setDisplayHomeAsUpEnabled(true);

        enableStartProtoButton(false);
        enableEndProtoButton(false);

        //byte[] test_b = getBytesForUserParameters();

        populateScheduleArray();

        /*
         * BLE INITIALIZATION
         */
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }

        //making sure BLE is available, enabled and fine location permission is granted:
        if (!hasPermissions()) {
            Toast toast = Toast.makeText(getApplicationContext(),
                    "Please enable Bluetooth and fine location permissions for the app",
                    Toast.LENGTH_LONG);
            toast.show();
            return;
        }

        textViewDevice = findViewById(R.id.stsView);

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        this.getApplicationContext().registerReceiver(bleBondReceiver, filter);
    }



    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy() called");
        this.getApplicationContext().unregisterReceiver(bleBondReceiver);
        super.onDestroy();
        if (mGatt == null) {
            return;
        }
        mGatt.disconnect();
        mGatt.close();
        mGatt = null;

        this.getApplicationContext().unregisterReceiver(bleBondReceiver);

    }

    private final BroadcastReceiver bleBondReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);

                switch(state) {
                    case BluetoothDevice.BOND_BONDING:
                        // Bonding...
                        break;

                    case BluetoothDevice.BOND_BONDED:
                        // Bonded...
                        //mActivity.unregisterReceiver(mReceiver);
                        mGatt.discoverServices();
                        break;

                    case BluetoothDevice.BOND_NONE:
                        // Not bonded...
                        break;
                }
            }
        }
    };


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.app_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                // show app setting ui
                Intent i = new Intent(this, MyPreferencesActivity.class);
                startActivity(i);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }

    }

    /**
     * ensures we have ble permissions as well as fine location enabed
     *
     * @return false if bluetooth permissions are missing - need localization permission for the app to function
     * true if permissions have been granted
     */
    private boolean hasPermissions() {
        return !(mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Starts BLE Scanning for about 10 seconds
     */
    private void StartBLEScan(final boolean enable) {

        BluetoothLeScanner mLEScanner = mBluetoothAdapter.getBluetoothLeScanner();

        if (enable && !mScanning) {
            // Building up a list of scan filters:
            // we only want to display the one with the device name that the barcode scanner picked up
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(1)
                    .build();

            Log.d("dbg", "Setting up to filter for " + devString);
            ScanFilter filter = new ScanFilter.Builder()
                    .setDeviceName(devString)
                    .build();
            Log.d("dbg", "Starting BLE Scan");
            mScanning = true;
            mLEScanner.startScan(Collections.singletonList(filter), settings, mLEScanCallBack);
        } else {
            Log.d("dbg", "Stopping BLE Scan");
            mScanning = false;
            mLEScanner.stopScan(mLEScanCallBack);
            mLEScanner.flushPendingScanResults(mLEScanCallBack);

        }

    }


    /**
     * ****  CALLBACK : WHEN BLE SCAN RESULTS ARE IN
     */
    private final ScanCallback mLEScanCallBack = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            Log.d("dbg", "should not be here");
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            if (!results.isEmpty()) {
                Log.d("dbg", "Have Scan result");
                ScanResult result = results.get(0);
                final BluetoothDevice device = result.getDevice();
                ScanRecord record = result.getScanRecord();

                //stopping the scan now:
                StartBLEScan(false);
                displayMessage(getString(R.string.connected_to, record != null ? record.getDeviceName() : null));

                mBLEDevice = device;

                //connecting and getting the services from the device:
                connectToDevice(device);
            }
        }
    };

    private boolean refreshDeviceCache(BluetoothGatt gatt) {
        try {
            BluetoothGatt localBluetoothGatt = gatt;
            Method localMethod = localBluetoothGatt.getClass().getMethod("refresh", new Class[0]);
            if (localMethod != null) {
                boolean bool = ((Boolean) localMethod.invoke(localBluetoothGatt, new Object[0])).booleanValue();
                return bool;
            }
        } catch (Exception localException) {
            Log.e(TAG, "An exception occured while refreshing device");
        }
        return false;
    }


    /**
     * FUNCTION:
     * <p>
     * CONNECT TO BLE DEVICE
     * <p>
     * Called after the BLE scan is stopped.
     * Note that there is a delay of 1000 milli seconds before the function is activated
     * to enable the Android BLE stack to actually stop the scanning process
     *
     * @param device : MAC adress of the device found during scan which matches the device name passed in from the barcode scanner
     */
    public void connectToDevice(final BluetoothDevice device) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() { //starting the connect with a delay after a closing BLEScan to make sure the scanning has really stopped
                if (mGatt == null) {
                    Log.d("dbg", "Connecting to Gatt");
                    mGatt = device.connectGatt(MainActivity.this, false, mGattCallback, 2);
                    //mGatt = device.connectGatt(this,false,mGattCallback);
                } else {
                    //closing connections first before reconnecting:
                    mGatt.disconnect();
                    mGatt.close();
                    mGatt = device.connectGatt(MainActivity.this, true, mGattCallback, 2);
                }
                refreshDeviceCache(mGatt);
                displayMessage("Connecting to device using the GATT library");

            }
        }, CONNECT_GATT_DELAY);

    }

    /**
     * ****  CALLBACK : GATT CALLBACK
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {


        /**
         * CALLBACK : WHEN DEVICE CONNECTION STATE CHANGES
         */
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            Log.d("dbg", "status = " + status);
            switch (newState) {
                case BluetoothAdapter.STATE_CONNECTED:
                    Log.d("dbg", "STATE CONNECTED");
                    displayMessage("STATE CONNECTED");
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (mBLEDevice != null)
                        {
                            if (mBLEDevice.getBondState() == BluetoothDevice.BOND_NONE)
                            {
                                mBLEDevice.createBond();
                            }
                            else
                            {
                                gatt.discoverServices();
                            }
                        }
//                        gatt.discoverServices();
                    } else {
                        if (mBLEDevice != null) {
                            connectToDevice(mBLEDevice);
                        }
                    }
                    break;
                case BluetoothAdapter.STATE_DISCONNECTED:
                    Log.d("dbg", "STATE DISCONNECTED");
                    //displayMessage("STATE DISCONNECTED");
                    mGatt.disconnect();
                    mGatt.close();
                    mGatt = null;
                    finish();
                    break;
                default:
                    Log.e("dbg", "ERROR UNKNOWN STATE");
                    displayMessage("UNEXPECTED STATE: Disconnecting.");
                    mGatt.disconnect();
                    mGatt.close();
                    mGatt = null;
                    finish();
            }
        }

        /**
         *  CALLBACK : WHEN SERVICES ARE DISCOVERED:
         *
         * @param gatt : currently connected device gatt database
         * @param status : connection state
         */
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            List<BluetoothGattService> services = gatt.getServices();
            int service_len = services.size();

            if (service_len == 0) { //we have an issue  - go back to the previous screen:
                mGatt.disconnect();
                mGatt.close();
                mGatt = null;
                finish();
            }
            Log.d("dbg", +service_len + " services discovered.");
            displayMessage(service_len + " services discovered.");
            parseCharacteristicsAndServices(gatt, services);

            if (tuart_tx_charac != null) {
                mGatt.setCharacteristicNotification(tuart_tx_charac, true);

                BluetoothGattDescriptor descriptor = tuart_tx_charac.getDescriptor(SampleGattAttributes.CCCD_UUID);
                if (descriptor != null) {
                    Log.d(TAG, "tuart tx charac SET NOTIFICATION VALUE");
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    mGatt.writeDescriptor(descriptor);
                } else {
                    Log.d(TAG, "tuart tx charac descriptor is null");
                }
            }

//            runOnUiThread(new Runnable() {
//                @Override
//                public void run() {
//                    enableStartProtoButton(true);
//                    enableScanButton(false);
//                }
//            });


        }


        /**
         * CALLBACK : WHEN CHARACTERISTICS ARE READ
         * @param gatt : currently connected device gatt database
         * @param characteristic - UUID
         * @param status - connection state
         */
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            if (status == 0) {
                if (characteristic.getUuid().equals(SampleGattAttributes.TUART_TX_UUID)) {
                    byte[] data = characteristic.getValue();
                    String recv_str = new String(data);
                    Log.d(TAG, "T UART Read = " + recv_str);

                    BluetoothGattCharacteristic tmpCharac = tuart_rx_charac;
                    tmpCharac.setValue("world12345".getBytes());
                    Log.d(TAG, "write T UART Rx world12345");
                    mGatt.writeCharacteristic(tmpCharac);

                }


            }

        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            String charac_uuid_str = characteristic.getUuid().toString();
            Log.d(TAG, String.format("characteristic write completed succ, %s sts = %d", charac_uuid_str, status));
            String charac_name = SampleGattAttributes.lookup(charac_uuid_str, "Not found");
            Log.d(TAG, charac_name);

            if (charac_uuid_str.equals(SampleGattAttributes.TUART_TX_CHARAC)) {
                Log.d(TAG, " succ write tuart Tx charac");
            } else if (charac_uuid_str.equals(SampleGattAttributes.TUART_RX_CHARAC)) {
                Log.d(TAG, " succ write tuart Rx charac");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            String charac_uuid_str = characteristic.getUuid().toString();
            Log.d(TAG, String.format("%s charac changed %s", charac_uuid_str, SampleGattAttributes.lookup(charac_uuid_str, "not found name")));
            if (characteristic.getUuid().equals(tuart_tx_charac.getUuid())) {
                byte[] data = characteristic.getValue();
                Log.d(TAG, String.format("data len recv = %d", data.length));
                StringBuilder stringBuilder = new StringBuilder();
                for (byte byteChar : data)
                    stringBuilder.append(String.format("%02X ", byteChar));
                Log.d("dbg", "TUART Tx = " + stringBuilder.toString());
                Log.d(TAG, "" + System.currentTimeMillis());
                if ((data[1] == 0x01) && (data[2] == TUART_HW2APP_CURRENT_SETTINGS)) {
                    // received current settings message, send send time sync and user parameters
                    byte[] resp_data = new byte[4 + 7 + 12]; // 4 => first byte ->size, second byte num of items, third byte id, 7 bytes of value, second charac id, 12 bytes of value
                    resp_data[0] = (byte) resp_data.length;
                    resp_data[1] = 2;
                    resp_data[2] = TUART_APP2HW_TIME_SYNC;
                    //System.arraycopy(new byte[]{119, 3,21, 10, 45, 54, 1}, 0, resp_data, 3, 7);
                    System.arraycopy(getBytesForTimeSync(), 0, resp_data, 3, 7);
                    resp_data[10] = TUART_APP2HW_USER_SETTINGS;
//                    System.arraycopy(new byte[]{15, 17, 19, 21, 22, 24, 26, 28, 31, 33, 36, 39}, 0, resp_data, 11, 12);
                    System.arraycopy(getBytesForUserParameters(), 0, resp_data, 11, 12);

                    tuart_tx_charac.setValue(resp_data);
                    mGatt.writeCharacteristic(tuart_tx_charac);
                    Log.d(TAG, "WRITE TIME SYNC + user settings ");
                    displayMessage("Recvd Current Setting. Wrote Time Sync and User setting");
                } else if ((data[1] == 0x01) && (data[2] == TUART_HW2APP_STATUS)) {
                    // send schedules
                    if (schedule_index < schedule_arr.length) {
                        int write_data_len;
                        if ((schedule_arr.length - schedule_index) > (TUART_BUF_LENGTH - 3)) {
                            write_data_len = TUART_BUF_LENGTH - 3;
                            write_data_len = write_data_len - (write_data_len % 5);
                        } else {
                            write_data_len = schedule_arr.length - schedule_index;
                        }
                        byte[] send_data = new byte[3 + write_data_len];
                        send_data[0] = (byte) send_data.length;
                        send_data[1] = 0x01;
                        send_data[2] = TUART_APP2HW_SCHEDULES;
                        System.arraycopy(schedule_arr, schedule_index, send_data, 3, write_data_len);
                        tuart_tx_charac.setValue(send_data);
                        mGatt.writeCharacteristic(tuart_tx_charac);
                        Log.d(TAG, String.format("sent schedules from %d to %d ", schedule_index, schedule_index + write_data_len));
                        displayMessage(String.format("Recvd HW2App sts. sent schedules from %d to %d ", schedule_index, schedule_index + write_data_len));
                        schedule_index += write_data_len;
                    } else {
                        //NOTHIME MORE TO SEND
                        Log.d(TAG, "NOT EXPECTING TUART_HW2APP_STATUS. All Schedules sent");
                        byte[] send_data = new byte[8];
                        send_data[0] = 0x08;
                        send_data[1] = 0x01;
                        send_data[2] = TUART_APP2HW_SCHEDULES;
                        send_data[3] = (byte) 0xFF;
                        send_data[4] = 0x00;
                        send_data[5] = 0x00;
                        send_data[6] = 0x00;
                        send_data[7] = 0x00;
                        tuart_tx_charac.setValue(send_data);
                        mGatt.writeCharacteristic(tuart_tx_charac);
                        Log.d(TAG, "sent end of schedules message ");
                        displayMessage("Sent end of Schedule Message");
                    }
                } else if ((data[1] == 0x01) && (data[2] == TUART_HW2APP_TEMPERATURE_BUF)) {
                    byte num_elements_recvd = data[8];
                    byte[] send_data = new byte[4];
                    send_data[0] = (byte) send_data.length;
                    send_data[1] = 0x01;
                    send_data[2] = TUART_APP2HW_STATUS;
                    send_data[3] = num_elements_recvd;
                    tuart_tx_charac.setValue(send_data);
                    mGatt.writeCharacteristic(tuart_tx_charac);
                    Log.d(TAG, String.format("Recvd Temperature buffer: num elements => %d", num_elements_recvd));
                    Log.d(TAG, "sent temp buf ACK response");
                    displayMessage(String.format("Recvd Temperature buffer: num elements => %d", num_elements_recvd));
                    displayMessage("sent temp buf ACK response");
                } else if ((data[1] == 0x01) && (data[2] == TUART_HW2APP_RUNTIME_BUF)) {
                    byte num_elements_recvd = data[8];
                    byte[] send_data = new byte[4];
                    send_data[0] = (byte) send_data.length;
                    send_data[1] = 0x01;
                    send_data[2] = TUART_APP2HW_STATUS;
                    send_data[3] = num_elements_recvd;
                    tuart_tx_charac.setValue(send_data);
                    mGatt.writeCharacteristic(tuart_tx_charac);
                    Log.d(TAG, String.format("Recvd rUNTIME buffer: num elements => %d", num_elements_recvd));
                    Log.d(TAG, "sent runtime buf ACK response");
                    displayMessage(String.format("Recvd runtime buffer: num elements => %d", num_elements_recvd));
                    displayMessage("sent runtime buf ACK response");
                } else if ((data[1] == 0x01) && (data[2] == TUART_HW2APP_SETPOINT_BUF)) {
                    byte num_elements_recvd = data[8];
                    byte[] send_data = new byte[4];
                    send_data[0] = (byte) send_data.length;
                    send_data[1] = 0x01;
                    send_data[2] = TUART_APP2HW_STATUS;
                    send_data[3] = num_elements_recvd;
                    tuart_tx_charac.setValue(send_data);
                    mGatt.writeCharacteristic(tuart_tx_charac);
                    Log.d(TAG, String.format("Recvd setpoint buffer: num elements => %d", num_elements_recvd));
                    Log.d(TAG, "sent runtime buf ACK response");
                    displayMessage(String.format("Recvd setpoint buffer: num elements => %d", num_elements_recvd));
                    displayMessage("sent runtime buf ACK response");
                } else if (java.util.Arrays.equals(data, new byte[]{0x4B, 0x2C, 0x31, 0x0D})) {
                    mGatt.disconnect();
                } else if (java.util.Arrays.equals(data, new byte[]{0x53, 0x4F, 0x2C, 0x31, 0x0D})) {
                    // do nothing it is go to low power mode for rn4871 which has come up here.
                }
//                else if (data[1] == 0x04) {
//                    data[0] = (byte)(data[1] + 4);
//                    data[1] = (byte)(data[2] + 6);
//                    data[2] = (byte)(data[3] + 8);
//                    data[3] = (byte)(data[4] + 5);
//                    data[4] = (byte)(data[5] + 7);
//                    data[5] = (byte)(data[6] + 9);
//                    data[6] = (byte)(data[7] + 10);
//                    tuart_tx_charac.setValue(data);
//                    mGatt.writeCharacteristic(tuart_tx_charac);
//
//                }
                else {
                    Log.d(TAG, "Unknown proto bytes received");
                    displayMessage("Unknown proto bytes received");
                }
            }


        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            BluetoothGattCharacteristic charac = descriptor.getCharacteristic();

            if (charac.getUuid().equals(SampleGattAttributes.CCCD_UUID)) {
                Log.d(TAG, "recvd write descriptor for CCCD_UUID");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        enableStartProtoButton(true);
                        enableScanButton(false);
                    }
                });

            }
        }
    }; //END BLUETOOTH GATT CALLBACK

    public void parseCharacteristicsAndServices(BluetoothGatt gatt, List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        //ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        //ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
        //        = new ArrayList<ArrayList<HashMap<String, String>>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            Log.d(TAG, String.format("gatt service uuid %s", gattService.getUuid().toString()));
            if (gattService.getUuid().equals(UUID.fromString(SampleGattAttributes.TUART_SERVICE))) {
                tUartService = gattService;
                displayMessage("GATT service TUART was discovered");
            }
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();

            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                //charas.add(gattCharacteristic);
                //HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                if (uuid.equals(SampleGattAttributes.TUART_RX_CHARAC)) {
                    Log.d(TAG, String.format("tuart Rx charac props => %02x", gattCharacteristic.getProperties()));
                    tuart_rx_charac = gattCharacteristic;
                    displayMessage(String.format("found TUART Rx charac prop => %02x", gattCharacteristic.getProperties()));
                } else if (uuid.equals(SampleGattAttributes.TUART_TX_CHARAC)) {
                    Log.d(TAG, String.format("tuart Tx charac props => %02x", gattCharacteristic.getProperties()));
                    tuart_tx_charac = gattCharacteristic;
                    displayMessage(String.format("found TUART Tx charac prop => %02x", gattCharacteristic.getProperties()));
                }
            }
        }
    }

    private void enableStartProtoButton(boolean isEnabled) {
        Button btn = (Button) findViewById(R.id.start_proto_btn);
        btn.setEnabled(isEnabled);
    }

    private void enableEndProtoButton(boolean isEnabled) {
        Button btn2 = (Button) findViewById(R.id.end_proto_btn);
        btn2.setEnabled(isEnabled);
    }

    private void enableScanButton(boolean isEnabled) {
        Button btn2 = (Button) findViewById(R.id.scan_btn);
        btn2.setEnabled(isEnabled);
    }


    public void startDeviceScan(View view) {
        //SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);

        StartBLEScan(true);
    }

    public void startProtocol(View view) {
        if (mGatt == null) {
            return;
        }
        if (tuart_tx_charac != null) {
            //mGatt.readCharacteristic(tuart_tx_charac);
            enableStartProtoButton(false);
            enableEndProtoButton(true);
        } else {
            displayMessage("Unable to start protocol.");
            displayMessage("Device does not have required characs.");
        }
    }

    public void stopProtocol(View view) {
        if (mGatt == null) {
            return;
        } else {
            mGatt.disconnect();
            mGatt.close();
            mGatt = null;
        }
        enableStartProtoButton(false);
        enableEndProtoButton(false);
        enableScanButton(true);
    }

    private void populateScheduleArray() {
        int kk = 0;
        int hh = 5;
        int mm = 18;
        for (int ii = 0; ii < 7; ii++) {
            for (int jj = 0; jj < 5; jj++) {
                schedule_arr[kk] = (byte) (ii << 4 | jj);
                schedule_arr[kk + 1] = (byte) hh;
                hh = (hh + 1) % 24;
                schedule_arr[kk + 2] = (byte) mm;
                mm = (mm + 1) % 60;
                schedule_arr[kk + 3] = (byte) (65 + jj);
                schedule_arr[kk + 4] = (byte) (84 - jj);
                kk += 5;
            }
            hh += 2;
            hh = hh % 24;
            mm += 5;
            mm = mm % 60;
        }
        schedule_arr[kk] = (byte) 0xFF;
        schedule_arr[kk + 1] = (byte) 0;
        schedule_arr[kk + 2] = (byte) 0;
        schedule_arr[kk + 3] = (byte) 0;
        schedule_arr[kk + 4] = (byte) 0;
    }

    private void displayMessage(final String str) {
//            runOnUiThread(new Runnable() {
//                @Override
//                public void run() {
//                    enableStartProtoButton(true);
//                    enableScanButton(false);
//                }
//            });
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (textViewDevice != null) {
                    textViewDevice.append(str + "\n");
                }
            }
        });
    }

    private String getHexIn2Digit(int to_conv) {
        String tmphex = Integer.toString(to_conv, 16);
        if (tmphex.length() <= 1) {
            tmphex = "0" + tmphex;
        }
        return tmphex;
    }

    private byte[] getBytesForTimeSync() {
        Calendar calendar = Calendar.getInstance();
        byte[] retb = new byte[7];
        retb[0] = (byte) (calendar.get(Calendar.YEAR) - 1900);
        retb[1] = (byte) (calendar.get(Calendar.MONTH));
        retb[2] = (byte) (calendar.get(Calendar.DAY_OF_MONTH));
        retb[3] = (byte) (calendar.get(Calendar.HOUR_OF_DAY));
        retb[4] = (byte) (calendar.get(Calendar.MINUTE));
        retb[5] = (byte) (calendar.get(Calendar.SECOND));
        if (TimeZone.getDefault().inDaylightTime(new Date())) {
            retb[6] = 1;
        } else {
            retb[6] = 0;
        }
        return retb;
    }

    private byte[] getBytesForUserParameters() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
        byte[] retb = new byte[12];
        BitSet bitSet = new BitSet(8);
        String tmpstr;
        // toByteArray()[0] gives the first 8 bits of byte
        if (sharedPreferences.getBoolean("timer_setting", false) == true) {
            bitSet.set(0);
            tmpstr = sharedPreferences.getString("timer_minutes_edittext", "5");
            int timer_off_mins;

            try {
                timer_off_mins = Integer.parseInt(tmpstr, 10);
            } catch (NumberFormatException e) {
                e.printStackTrace();
                timer_off_mins = 4;
            }
            retb[5] = (byte) timer_off_mins;
        }
        else
        {
            bitSet.set(0, false);
        }

        if (sharedPreferences.getBoolean("cool_heat_setpoint", false) == true) {
            bitSet.set(1);
            int cool_sp;
            int heat_sp;
            try {
                cool_sp = Integer.parseInt(sharedPreferences.getString("cool_tb", "78"));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                cool_sp = 79;
            }
            try {
                heat_sp = Integer.parseInt(sharedPreferences.getString("heat_tb", "65"));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                heat_sp = 64;
            }
            retb[3] = (byte) cool_sp;
            retb[4] = (byte) heat_sp;
        }
        else
        {
            bitSet.set(1, false);
        }

        if (sharedPreferences.getBoolean("hysteresis_setting", false) == true) {
            bitSet.set(2);
            int hysteris_val;
            try {
                hysteris_val = Integer.parseInt(sharedPreferences.getString("hysteresis_tb", "1"));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                hysteris_val = 1;
            }
            retb[2] = (byte) hysteris_val;
        }
        else
        {
            bitSet.set(2, false);
        }

        if (sharedPreferences.getBoolean("mode_setting", false) == true)
        {
            bitSet.set(3);
            int mode_val;
            try {
                mode_val = Integer.parseInt(sharedPreferences.getString("mode_tb", "1"));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                mode_val = 1;
            }
            retb[1] = (byte) mode_val;
        }
        else
        {
            bitSet.set(3, false);
        }

        if (sharedPreferences.getBoolean("schedule_setting", false) == true)
        {
            bitSet.set(4);
            int sch_val;
            try {
                sch_val = Integer.parseInt(sharedPreferences.getString("schedule_tb", "1"));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                sch_val = 1;
            }
            retb[6] = (byte)sch_val;
            if (sch_val == 0x02) // away mode
            {
                retb[7] = 0x01;
                retb[8] = (byte)82;
                retb[9] = (byte) 52;
            }
        }
        else
        {
            bitSet.set(4, false);
        }

        if (sharedPreferences.getBoolean("devicepower_setting", false) == true)
        {
            bitSet.set(5);
            int onoff_val;
            try {
                onoff_val = Integer.parseInt(sharedPreferences.getString("powersetting_tb", "0"));
            } catch (NumberFormatException e) {
                e.printStackTrace();
                onoff_val = 0;
            }
            retb[10] = (byte) onoff_val;
        }
        else
        {
            bitSet.set(5, false);
        }

        // TODO: DROptOut preference test

        // bitset not working the next line giving outofindexexception
        //retb[0] = bitSet.toByteArray()[0];
        retb[0] = 0;
        return retb;
    }

}