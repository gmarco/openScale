/* Copyright (C) 2018  Marco Gittler <marco@gitma.de>
*
*    This program is free software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as published by
*    the Free Software Foundation, either version 3 of the License, or
*    (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

package com.health.openscale.core.bluetooth;

import android.content.Context;

import android.bluetooth.BluetoothGattService;

import com.health.openscale.core.OpenScale;
import com.health.openscale.core.datatypes.ScaleMeasurement;
import com.health.openscale.core.datatypes.ScaleUser;
import com.health.openscale.core.utils.Converters;

import java.util.Date;
import java.util.Calendar;
import java.util.UUID;

import timber.log.Timber;

public class BluetoothSenssun extends BluetoothCommunication {
    private final UUID MODEL_A_WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xfff0);
    private final UUID MODEL_A_WEIGHT_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff1); // read, notify
    private final UUID MODEL_A_CMD_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xfff2); // write only

    private final UUID MODEL_B_WEIGHT_MEASUREMENT_SERVICE = BluetoothGattUuid.fromShortCode(0xfff0);
    private final UUID MODEL_B_WEIGHT_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xffb2); // read, notify
    private final UUID MODEL_B_CMD_MEASUREMENT_CHARACTERISTIC = BluetoothGattUuid.fromShortCode(0xffb5); // write only

    private UUID cmdMeasurementCharacteristic;

    private boolean scaleGotUserData;
    private boolean scaleGotTime,scaleGotDate,savedUserData;
    private byte WeightFatMus = 0;
    private ScaleMeasurement measurement;

    public BluetoothSenssun(Context context) {
        super(context);
    }

    @Override
    public String driverName() {
        return "Senssun";
    }

    private void saveUserData(){
      if ( isBitSet(WeightFatMus,2)  && !savedUserData ) {
          addScaleData(measurement);
          WeightFatMus=0;
          savedUserData = true;
      }
    }

    private void doChecksum(byte[] message){
      byte verify = 0;
      for (int i = 1; i < message.length - 2; i++) {
          verify = (byte) (verify + message[i]);
      }
      message[message.length - 2] = verify;
    }

    private void sendToScale(){
      sendUserData();
      if (scaleGotUserData){
        sendDate();
        if (scaleGotDate){
          sendTime();
        }
      }
    }

    private void sendDate() {
        if (scaleGotDate){
          return;
        }
        Calendar cal = Calendar.getInstance();

        byte message[] = new byte[]{(byte)0xA5, (byte)0x30, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        message[2] = (byte)Integer.parseInt(Long.toHexString(Integer.valueOf(String.valueOf(cal.get(Calendar.YEAR)).substring(2))), 16);

        String DayLength=Long.toHexString(cal.get(Calendar.DAY_OF_YEAR));
        DayLength=DayLength.length()==1?"000"+DayLength:
                DayLength.length()==2?"00"+DayLength:
                        DayLength.length()==3?"0"+DayLength:DayLength;

        message[3]=(byte)Integer.parseInt(DayLength.substring(0,2), 16);
        message[4]=(byte)Integer.parseInt(DayLength.substring(2,4), 16);

        doChecksum(message);

        writeBytes(cmdMeasurementCharacteristic, message);
    }

    private void sendTime() {
        if (scaleGotTime){
          return;
        }
        Calendar cal = Calendar.getInstance();

        byte message[] = new byte[]{(byte)0xA5, (byte)0x31, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};

        message[2]=(byte)Integer.parseInt(Long.toHexString(cal.get(Calendar.HOUR_OF_DAY)), 16);
        message[3]=(byte)Integer.parseInt(Long.toHexString(cal.get(Calendar.MINUTE)), 16);
        message[4]=(byte)Integer.parseInt(Long.toHexString(cal.get(Calendar.SECOND)), 16);

        doChecksum(message);

        writeBytes(cmdMeasurementCharacteristic, message);
  	}

    private void sendUserData(){
        if ( scaleGotUserData ){
          return;
        }
        final ScaleUser selectedUser = OpenScale.getInstance().getSelectedScaleUser();

        byte gender = selectedUser.getGender().isMale() ? (byte)0xf1 : (byte)0x01;
        byte height = (byte) selectedUser.getBodyHeight(); // cm
        byte age = (byte) selectedUser.getAge();

        Timber.d("Request Saved User Measurements ");
        byte cmdByte[] = {(byte)0xa5, (byte)0x10, gender, age, height, (byte)0, (byte)0x0, (byte)0x0d2, (byte)0x00};

        doChecksum(cmdByte);

        writeBytes(cmdMeasurementCharacteristic, cmdByte);
    }

    @Override
    protected boolean nextInitCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                for (BluetoothGattService gattService : getBluetoothGattServices()) {
                  if (gattService.getUuid().equals(MODEL_A_WEIGHT_MEASUREMENT_SERVICE)) {
                    cmdMeasurementCharacteristic = MODEL_A_CMD_MEASUREMENT_CHARACTERISTIC;
                    setNotificationOn(MODEL_A_WEIGHT_MEASUREMENT_CHARACTERISTIC);
                    Timber.d("Found a Model A");
                    break;
                  }
                  if (gattService.getUuid().equals(MODEL_B_WEIGHT_MEASUREMENT_SERVICE)) {
                    cmdMeasurementCharacteristic = MODEL_B_CMD_MEASUREMENT_CHARACTERISTIC;
                    setNotificationOn(MODEL_B_WEIGHT_MEASUREMENT_CHARACTERISTIC);
                    Timber.d("Found a Model B");
                    break;
                  }
                }
                WeightFatMus = 0;
                scaleGotUserData = false;
                scaleGotDate = false;
                scaleGotTime = false;
                savedUserData = false;
                break;
            case 1:
                sendToScale();

                break;
            default:
                // Finish init if everything is done
                return false;
        }
        return true;
    }

    @Override
    protected boolean nextBluetoothCmd(int stateNr) {
        return false;
    }

    @Override
    protected boolean nextCleanUpCmd(int stateNr) {
        switch (stateNr) {
            case 0:
                saveUserData();
                break;
            default:
                return false;
        }
        return true;
    }

    @Override
    public void onBluetoothNotify(UUID characteristic, byte[] value) {
        final byte[] data = value;

        // The first notification only includes weight and all other fields are
        // either 0x00 (user info) or 0xff (fat, water, etc.)

        if (data != null && !isBitSet(WeightFatMus, 3)) { //only if not saved
            parseBytes(data);
            if (WeightFatMus == 0x07) { // if we got all Data -> save
              saveUserData();
            }
        }


    }

    private void parseBytes(byte[] weightBytes) {
        if (measurement == null) {
            measurement = new ScaleMeasurement();
        }
        int type = weightBytes[6] & 0xff;
        Timber.d("type %02X", type);
        switch (type) {
            case 0x00:
                if (weightBytes[2] == (byte)0x10) {
                    scaleGotUserData = true;
                }
                if (weightBytes[2] == (byte)0x30) {
                    scaleGotDate = true;
                }
                if (weightBytes[2] == (byte)0x31) {
                    scaleGotTime = true;
                }
                break;
            case 0xa0:
                sendToScale();
                break;
            case 0xaa:
                float weight = Converters.fromUnsignedInt16Be(weightBytes, 2) / 10.0f; // kg
                measurement.setWeight(weight);

                if (!isBitSet(WeightFatMus,2)){
                  WeightFatMus |= 1 << 2 ;
                }

                sendUserData();
                break;
            case 0xb0:
                float fat = Converters.fromUnsignedInt16Be(weightBytes, 2) / 10.0f; // %
                float water = Converters.fromUnsignedInt16Be(weightBytes, 4) / 10.0f; // %
                measurement.setFat(fat);
                measurement.setWater(water);
                WeightFatMus |= 1 << 1;
                break;
            case 0xc0:
                float bone = Converters.fromUnsignedInt16Le(weightBytes, 4) / 10.0f; // kg
                float muscle = Converters.fromUnsignedInt16Be(weightBytes, 2) / 10.0f; // %
                measurement.setMuscle(muscle);
                measurement.setBone(bone);
                WeightFatMus |= 1;
                break;
            case 0xd0:
                float calorie = Converters.fromUnsignedInt16Be(weightBytes, 2);
                break;
            case 0xe0:
                break;
            case 0xe1:
                break;
            case 0xe2:
                //date
                break;
        }
        measurement.setDateTime(new Date());

    }
}
