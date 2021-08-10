package com.eveningoutpost.dexdrip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.format.DateFormat;

import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.Calibration;
import com.eveningoutpost.dexdrip.Models.Forecast;
import com.eveningoutpost.dexdrip.Models.Forecast.PolyTrendLine;
import com.eveningoutpost.dexdrip.Models.Forecast.TrendLine;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Libre2RawValue;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.Models.UserError.Log;
import com.eveningoutpost.dexdrip.UtilityModels.BgGraphBuilder;
import com.eveningoutpost.dexdrip.UtilityModels.Intents;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.UtilityModels.StatusItem;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import java.util.Comparator;
import java.util.Collections;

import static com.eveningoutpost.dexdrip.Home.get_engineering_mode;
import static com.eveningoutpost.dexdrip.Models.Libre2Sensor.Libre2Sensors;

/**
 * Created by jamorham on 14/11/2016.
 * Modified by capflamme on 25/05/2021.
 */

public class LibreReceiver extends BroadcastReceiver {

    private static final String TAG = "xdrip libre_receiver";
    private static final boolean debug = false;
    private static final boolean d = false;
    private static SharedPreferences prefs;
    private static final Object lock = new Object();
    private static String libre_calc_doku="wait for next reading...";
    private static long last_reading = 0;
    public static double libre2Noise = -99999;
    public static TrendLine libre2NoisePoly;

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if(DexCollectionType.getDexCollectionType() != DexCollectionType.LibreReceiver)
            return;
        new Thread() {
            @Override
            public void run() {
                PowerManager.WakeLock wl = JoH.getWakeLock("libre-receiver", 60000);
                synchronized (lock) {
                    try {

                        Log.d(TAG, "libre onReceiver: " + intent.getAction());
                        JoH.benchmark(null);
                        // check source
                        if (prefs == null)
                            prefs = PreferenceManager.getDefaultSharedPreferences(context);

                        final Bundle bundle = intent.getExtras();
                        //  BundleScrubber.scrub(bundle);
                        final String action = intent.getAction();



                        if (action == null) return;

                        switch (action) {
                            case Intents.LIBRE2_ACTIVATION:
                                Log.v(TAG, "Receiving LibreData activation");
                                try {
                                    saveSensorStartTime(intent.getBundleExtra("sensor"), intent.getBundleExtra("bleManager").getString("sensorSerial"));
                                } catch (NullPointerException e) {
                                    Log.e(TAG, "Null pointer in LIBRE2_ACTIVATION: " + e);
                                }
                                break;

                            case Intents.LIBRE2_BG:
                                Libre2RawValue currentRawValue = processIntent(intent);
                                if (currentRawValue == null) return;
                                Log.v(TAG,"got bg reading: from sensor: "+ currentRawValue.serial + ", rawValue:" + JoH.qs(currentRawValue.glucose,1) +" at: " + JoH.dateTimeText(currentRawValue.timestamp));
                                // period of 4.5 minutes to collect 5 readings
                                // if(!BgReading.last_within_millis(45 * 6 * 1000 )) {
                                // modified to set the refresh interval for BgReading calculation in the libre 2 advanced preferences.
                                Log.v(TAG,"Updating readings every " + Pref.getStringToLong("Libre2ReadingInterval", 5) +" min");
                                List<Libre2RawValue> libre2NoiseRawValues = Libre2RawValue.last20Minutes();
                                libre2NoiseRawValues.add(currentRawValue);
                                libre2NoisePoly = poly(libre2NoiseRawValues,2);

                                if (libre2NoisePoly == null) {
                                    libre2Noise = -9999;
                                }
                                else {
                                    libre2Noise = libre2NoisePoly.errorVarience();
                                }
                                if(!BgReading.last_within_millis(((Pref.getStringToLong("Libre2ReadingInterval", 5)*60)-30)*1000)) {
                                    Log.v(TAG,DateFormat.format("mm:ss",(JoH.tsl() - BgReading.last().timestamp)) + " elapsed since last BG reading. Triggering a new one.");
                                    List<Libre2RawValue> smoothingValues;
                                    Log.v(TAG,"SmoothingMethod:"+ Pref.getString("Libre2SmoothingMethod", "Default"));
                                    if (!(Pref.getString("Libre2SmoothingMethod", "Default").equals("Default"))) {
                                        int smoothing_window = 20;
                                        if (libre2Noise != -9999) {
                                            int smoothing_window_min = Pref.getStringToInt("Smoothing_duration_min", 20);
                                            int smoothing_window_max = Pref.getStringToInt("Smoothing_duration_max", 20);
                                            if ( (libre2Noise > 0) && (libre2Noise <= BgGraphBuilder.NOISE_TRIGGER_ULTRASENSITIVE)) {
                                                smoothing_window = smoothing_window_min;
                                            } else if ( libre2Noise <= BgGraphBuilder.NOISE_TRIGGER){
                                                smoothing_window = smoothing_window_min + ((smoothing_window_max - smoothing_window_min) / 5);
                                            } else if ( libre2Noise <= BgGraphBuilder.NOISE_TOO_HIGH_FOR_PREDICT){
                                                smoothing_window = smoothing_window_min + (2 * (smoothing_window_max - smoothing_window_min) / 5);
                                            } else if ( libre2Noise <= BgGraphBuilder.NOISE_FORGIVE){
                                                smoothing_window = smoothing_window_min + (3 * (smoothing_window_max - smoothing_window_min) / 5);
                                            } else if ( libre2Noise <= BgGraphBuilder.NOISE_HIGH){
                                                smoothing_window = smoothing_window_min + (4 * (smoothing_window_max - smoothing_window_min) / 5);
                                            } else if ( libre2Noise > BgGraphBuilder.NOISE_HIGH) {
                                                smoothing_window = smoothing_window_max;
                                            }
                                        }
                                        Log.d(TAG,"Noise: errorVarience: " + JoH.qs(libre2Noise, 2));
                                        Log.d(TAG,"Smoothing window set to " + smoothing_window + " min.");
                                        smoothingValues = Libre2RawValue.lastVariableMinutes(smoothing_window);
                                    }
                                    else {
                                        smoothingValues = Libre2RawValue.last20Minutes();
                                    }
                                    smoothingValues.add(currentRawValue);
                                    processValues(currentRawValue, smoothingValues, context);
                                }
                                else {
                                    Log.v(TAG,DateFormat.format("mm:ss",(JoH.tsl() - BgReading.last().timestamp)) + " elapsed since last BG reading. Not triggering a new one yet.");
                                }
                                currentRawValue.save();

                                break;

                            default:
                                Log.e(TAG, "Unknown action! " + action);
                                break;
                        }
                    } finally {
                        JoH.benchmark("NSEmulator process");
                        JoH.releaseWakeLock(wl);
                    }
                } // lock
            }
        }.start();
    }

    private static Libre2RawValue processIntent(Intent intent) {
        Bundle sas = intent.getBundleExtra("sas");
        try {
            if (sas != null)
                saveSensorStartTime(sas.getBundle("currentSensor"), intent.getBundleExtra("bleManager").getString("sensorSerial"));
        } catch (NullPointerException e) {
            Log.e(TAG,"Null pointer exception in processIntent: " + e);
        }
        if (!intent.hasExtra("glucose") || !intent.hasExtra("timestamp") || !intent.hasExtra("bleManager")) {
            Log.e(TAG,"Received faulty intent from LibreLink.");
            return null;
        }
        double glucose = intent.getDoubleExtra("glucose", 0);
        long timestamp = intent.getLongExtra("timestamp", 0);
        last_reading = timestamp;
        String serial = intent.getBundleExtra("bleManager").getString("sensorSerial");
        if (serial == null) {
            Log.e(TAG,"Received faulty intent from LibreLink.");
            return null;
        }
        Libre2RawValue rawValue = new Libre2RawValue();
        rawValue.timestamp = timestamp;
        rawValue.glucose = glucose;
        rawValue.serial = serial;
        return rawValue;
    }
    private static void processValues(Libre2RawValue currentValue, List<Libre2RawValue> smoothingValues, Context context) {
        if (Sensor.currentSensor() == null) {
            Sensor.create(currentValue.timestamp, currentValue.serial);
        }
        double value;
        if (Pref.getString("Libre2SmoothingMethod", "Default").equals("Median")) {
            value = calculateMedian(smoothingValues);
        }
        else if (Pref.getString("Libre2SmoothingMethod", "Default").equals("WeightedAverage")) {
            value = calculateWeightedAverageWithDuration(smoothingValues);
        }
        else if (Pref.getString("Libre2SmoothingMethod", "Default").equals("WeightedExtremeRemovedAVG")) {
            value = calculateExtremeRemovedWeightedAverage(smoothingValues);
        }
        else if (Pref.getString("Libre2SmoothingMethod", "Default").equals("SavitzkyGolay")) {
            value = calculateSavitzkyGolay(smoothingValues);
        }
        else if (Pref.getString("Libre2SmoothingMethod", "Default").equals("AutomaticTrendline")) {
            value = calculatePoly(smoothingValues);
        }
        else {
            value = calculateWeightedAverage(smoothingValues, currentValue.timestamp);
        }
        BgReading.bgReadingInsertLibre2(value, currentValue.timestamp,currentValue.glucose);
    }

    private static void saveSensorStartTime(Bundle sensor, String serial) {
        if (sensor != null && sensor.containsKey("sensorStartTime")) {
            long sensorStartTime = sensor.getLong("sensorStartTime");

            Sensor last = Sensor.currentSensor();
            if(last!=null) {
                if (!last.uuid.equals(serial)) {
                    Sensor.stopSensor();
                    last = null;
                }
            }

            if(last==null) {
                Sensor.create(sensorStartTime,serial);
            }
        }
    }

    private static long SMOOTHING_DURATION = TimeUnit.MINUTES.toMillis(25);

    private static double calculateWeightedAverage(List<Libre2RawValue> rawValues, long now) {
        double sum = 0;
        double weightSum = 0;
        DecimalFormat longformat = new DecimalFormat( "#,###,###,##0.00" );

        libre_calc_doku="";
        for (Libre2RawValue rawValue : rawValues) {
            double weight = 1 - ((now - rawValue.timestamp) / (double) SMOOTHING_DURATION);
            sum += rawValue.glucose * weight;
            weightSum += weight;
            libre_calc_doku += DateFormat.format("kk:mm:ss :",rawValue.timestamp) + " w:" + longformat.format(weight) +" raw: " + rawValue.glucose  + "\n" ;
        }
        return Math.round(sum / weightSum);
    }

    // Start of Smoothing algos by Capflamme
    public static TrendLine poly(List<Libre2RawValue> RawValues, int degree ) {
        TrendLine poly = new PolyTrendLine(degree);
        if (RawValues.size() > 5) {
            final double[] polyXs = new double[RawValues.size()];
            final double[] polyYs = new double[RawValues.size()];
            int i = 0;
            try {
                for (Libre2RawValue rawValue : RawValues) {
                    polyXs[i] = (double) rawValue.timestamp;
                    polyYs[i] = rawValue.glucose;
                    i++;
                }
                UserError.Log.d(TAG, "Poly list size: " + polyXs.length);
                    poly.setValues(polyYs, polyXs);
            } catch (Exception e) {
                Log.e(TAG, " Error generating noise poly trend: " + e.toString());
                poly = null;
            }
        }
        else {
            UserError.Log.d(TAG, "Poly list size to small for reliable caluclation.");
            poly = null;
        }
        return poly;
    }

    private static double calculateWeightedAverageWithDuration(List<Libre2RawValue> rawValues) {
        double sum = 0;
        double weightSum = 0;
        int listSize = rawValues.size();
        double latestTimestamp = rawValues.get(listSize-1).timestamp;
        double duration = latestTimestamp - rawValues.get(0).timestamp;
        Log.v(TAG,"SmoothingValues: " + listSize + ", LatestRawValueTime: " + DateFormat.format("kk:mm:ss :",rawValues.get(listSize-1).timestamp) + ", LatestRawValue:" + rawValues.get(listSize-1).glucose + ", OldestValueTime: " + DateFormat.format("kk:mm:ss :",rawValues.get(0).timestamp) + ", OldestValue: " + rawValues.get(0).glucose);
        DecimalFormat longformat = new DecimalFormat( "#,###,###,##0.00" );

        libre_calc_doku="";
        for (Libre2RawValue rawValue : rawValues) {
            double weight = 1 - ((latestTimestamp - rawValue.timestamp) / duration);
            sum += rawValue.glucose * weight;
            weightSum += weight;
            libre_calc_doku += DateFormat.format("kk:mm:ss :",rawValue.timestamp) + " w:" + longformat.format(weight) +" raw: " + rawValue.glucose  + "\n" ;
        }
        libre_calc_doku += "\nWAVG: " + Math.round(sum / weightSum);
        return Math.round(sum / weightSum);
    }

    private static double calculateExtremeRemovedWeightedAverage(List<Libre2RawValue> rawValues) {
        double latestTimestamp;
        double duration;
        double sum = 0;
        double weightSum = 0;
        DecimalFormat longformat = new DecimalFormat("#,###,###,##0.00");
        double l2FilteredBg = -99999;
        try {
            libre_calc_doku = "Smoothing window: " + rawValues.size() + "min\n\n";
            if (rawValues.size() > 4) {
                Collections.sort(rawValues, new SortByGlucoseThenTimeInverted());
                libre_calc_doku += DateFormat.format("kk:mm:ss:", rawValues.get(0).timestamp) + " w:0.00" + " raw:" + rawValues.get(0).glucose + "#\n";
                rawValues.remove(0);
                Collections.sort(rawValues, new SortByGlucoseInvertedThenTimeInverted());
                libre_calc_doku += DateFormat.format("kk:mm:ss:", rawValues.get(0).timestamp) + " w:0.00" + " raw:" + rawValues.get(0).glucose + "#\n\n";
                rawValues.remove(0);
                Collections.sort(rawValues, new SortByTime());
            }
            latestTimestamp = rawValues.get(rawValues.size() - 1).timestamp;
            duration = latestTimestamp + TimeUnit.MINUTES.toMillis(1) - (rawValues.get(0).timestamp);
            for (Libre2RawValue rawValue : rawValues) {
                double weight = 1 - ((latestTimestamp - rawValue.timestamp) / duration);
                sum += rawValue.glucose * weight;
                weightSum += weight;
                libre_calc_doku += DateFormat.format("kk:mm:ss:", rawValue.timestamp) + " w:" + longformat.format(weight) + " raw:" + rawValue.glucose + "\n";
            }
            libre_calc_doku += "\nExtremeRemovedWAVG: " + Math.round(sum / weightSum);
            l2FilteredBg = Math.round(sum / weightSum);

        } catch (Exception e) {
            Log.e(TAG, "Error calcultating weighted average: " + e.toString());
            UserError.Log.i(TAG, "Error calcultating weighted average: " + e.toString());
        }
        UserError.Log.d(TAG, "BG: " + l2FilteredBg);
        return l2FilteredBg;
    }

    private static double calculateSavitzkyGolay(List<Libre2RawValue> rawValues) {
        double[][] SGCoefficients = {
                {1.0},
                {1.0,0.0},
                {0.83333,0.33333,-.16667},
                {0.7,0.4,0.1,-0.2},
                {0.6,0.4,0.2,0.0,-0.2},
                {0.52381,0.38095,0.2381,0.09524,-0.04762,-0.19048},
                {0.46429,0.35714,0.25,0.14286,0.03571,-0.07143,-0.17857},
                {0.41667,0.33333,0.25,0.16667,0.08333,0.0,-0.08333,-0.16667},
                {0.37778,0.31111,0.24444,0.17778,0.11111,0.04444,-0.02222,-0.08889,-0.15556},
                {0.34545,0.29091,0.23636,0.18182,0.12727,0.07273,0.01818,-0.03636,-0.09091,-0.14545},
                {0.31818,0.27273,0.22727,0.18182,0.13636,0.09091,0.04545,0.0,-0.04545,-0.09091,-0.13636},
                {0.29487,0.25641,0.21795,0.17949,0.14103,0.10256,0.0641,0.02564,-0.01282,-0.05128,-0.08974,-0.12821},
                {0.27473,0.24176,0.20879,0.17582,0.14286,0.10989,0.07692,0.04396,0.01099,-0.02198,-0.05495,-0.08791,-0.12088}
        };
        double BGValue = 0;
        int rawValuesSize = rawValues.size();
        DecimalFormat longformat = new DecimalFormat( "#,###,###,##0.00" );
        libre_calc_doku="";

        if (rawValuesSize > 13) {
            rawValues = rawValues.subList(rawValuesSize-13,rawValuesSize);
            rawValuesSize = rawValues.size();
        }
        Log.v(TAG,"rawValuesSize: " + rawValuesSize + ", rawValues: " + rawValues);
        int i = 0;
        for (Libre2RawValue rawValue : rawValues) {
            BGValue += (SGCoefficients[rawValuesSize -1][rawValuesSize - 1 - i]) * rawValue.glucose;
            libre_calc_doku += DateFormat.format("kk:mm:ss :", rawValue.timestamp) + " w:" + longformat.format(SGCoefficients[rawValuesSize -1][rawValuesSize - 1 - i]) + " raw:" + rawValue.glucose + "\n";
            i++;
        }
        libre_calc_doku += "\nSG value: " + Math.round(BGValue);
        return Math.round(BGValue);
    }

    private static double calculateMedian(List<Libre2RawValue> rawValues) {
        double median;
        List<Double> SortedBGArray = new ArrayList<>();
        double numberOfBGs;
        int MedianBGFloorIndex;
        libre_calc_doku="";
        for (Libre2RawValue rawValue : rawValues) {
            SortedBGArray.add(rawValue.glucose);
            libre_calc_doku += DateFormat.format("kk:mm:ss :",rawValue.timestamp) + " raw: " + rawValue.glucose  + "\n";
        }
        Collections.sort(SortedBGArray);
        numberOfBGs = SortedBGArray.size();
        if ( numberOfBGs % 2 == 0 ) {
            MedianBGFloorIndex = (int) Math.floor(numberOfBGs / 2)-1;
            median = Math.round((SortedBGArray.get(MedianBGFloorIndex) + SortedBGArray.get( MedianBGFloorIndex+1))/2);
            Log.v(TAG,"numberOfBGs: " + numberOfBGs + ", MedianBGFloorIndex: " + MedianBGFloorIndex + ", MedianBGFloor:" + SortedBGArray.get(MedianBGFloorIndex) + ", MedianBGRoofIndex: " + (MedianBGFloorIndex+1) + ", MedianBGRoof:" + SortedBGArray.get(MedianBGFloorIndex+1) + ", MedianBG:" + median);
        }
        else {
            MedianBGFloorIndex = (int) Math.floor(numberOfBGs / 2);
            median = SortedBGArray.get(MedianBGFloorIndex);
            Log.v(TAG,"numberOfBGs: " + numberOfBGs + ", MedianBGIndex: " + MedianBGFloorIndex + ", MedianBG:" + SortedBGArray.get(MedianBGFloorIndex));
        }
        libre_calc_doku += "\nMedian: " + median;
        return median;
    }

    private static double calculatePoly(List<Libre2RawValue> rawValues) {
        Forecast.TrendLine poly = new Forecast.PolyTrendLine(2);
        double EstimatedBG = -99999;
        final double[] polyxs;
        final double[] polyys;
        libre_calc_doku="";
        try {
            UserError.Log.d(TAG, "L2 poly smoothing: Processing new data from: " + JoH.dateTimeText((long) rawValues.get(0).timestamp) + " to: " + JoH.dateTimeText((rawValues.get(rawValues.size() - 1).timestamp)));
            UserError.Log.d(TAG, "L2 poly smoothing: Libre2RawValues size: " + rawValues.size());
            // transform rawValues in 2 arrays of timestamp and BG if there is at least 5 point.
            if (rawValues.size() > 5) {
                polyxs = new double[rawValues.size()];
                polyys = new double[rawValues.size()];
                int i = 0;
                libre_calc_doku += "Raw values:\n";
                for (Libre2RawValue rawValue : rawValues) {
                    polyxs[i] = (double) rawValue.timestamp;
                    polyys[i] = rawValue.glucose;
                    libre_calc_doku += DateFormat.format("kk:mm:ss :",rawValue.timestamp) +" raw: " + rawValue.glucose  + "\n";
                    i++;
                }
                poly.setValues(polyys, polyxs);
                double min_errors = poly.errorVarience();
                libre_calc_doku += "\nmodel: Poly. deg 2\nVar: " + JoH.qs(min_errors,2) + "\n";
                UserError.Log.d(TAG, "Set forecast best model to: " + poly.getClass().getSimpleName() + " with varience of: " + JoH.qs(min_errors,2));
                EstimatedBG = Math.round(poly.predict(polyxs[polyxs.length-1]));
                libre_calc_doku += "BG: " + JoH.qs((EstimatedBG / 18.018),1) + "\n";
                UserError.Log.d(TAG, "EstimatedBG: " + JoH.qs((EstimatedBG / 18.018)));
            } else {
                // otherwise, use calculateExtremeRemovedWeightedAverage(rawValues) smoothing
                UserError.Log.d(TAG, "Not enough data for forecast model, using calculateExtremeRemovedWeightedAverage instead.");
                EstimatedBG = calculateExtremeRemovedWeightedAverage(rawValues);
            }
            return EstimatedBG;
        } catch (Exception e) {
            android.util.Log.e(TAG, "L2 poly smoothing: Error with poly trend: " + e.toString());
            libre_calc_doku += "\nError with poly:\n" + e.toString();
            return EstimatedBG;
        }
    }

    /*
    private static double calculateLibre2Noise2(List<Libre2RawValue> RawValues, int WeightedAverageWindowMinutes) {
        double sigma = -1;
        double SumSquareDifToWAVG = 0;
        int j = 0;
        for ( Libre2RawValue RawValue: RawValues) {
            double BgWeightedAverage = 0;
            for (int i=0;i<2;i++) {
                List<Libre2RawValue> RawValuesforAverage = Libre2RawValue.latestForGraph(5,RawValue.timestamp - WeightedAverageWindowMinutes*30*1000 -60*1000*i, RawValue.timestamp + WeightedAverageWindowMinutes*30*1000 - 60*1000*i);
                UserError.Log.v(TAG,"j" + j + "i" + i + ": RawValuesforAverage.size:" + RawValuesforAverage.size() + ", windowStart:" + (RawValue.timestamp - (WeightedAverageWindowMinutes/2)*60*1000 -60*1000*i) + ", windowEnd:" + (RawValue.timestamp + (WeightedAverageWindowMinutes/2)*60*1000 -60*1000*i));
                if (RawValuesforAverage.size() >= WeightedAverageWindowMinutes) {
                    BgWeightedAverage = WeightedAverage(RawValuesforAverage, RawValue.timestamp);
                    SumSquareDifToWAVG = SumSquareDifToWAVG + Math.pow(BgWeightedAverage - RawValue.glucose, 2);
                    j++;
                    break;
                }
            }
            //UserError.Log.v(TAG,"j:" + j + ", BgWeightedAverage:" + BgWeightedAverage + ", SquareDif:" + (Math.pow((BgWeightedAverage - RawValue.glucose), 2)) + ", SumSquareDifToWAVG:" + SumSquareDifToWAVG);
            UserError.Log.v(TAG,"j:" + j + ": SumSquareDifToWAVG:" + SumSquareDifToWAVG);
        }

        if (j != 0) {
            sigma = Math.sqrt(SumSquareDifToWAVG / j);
        }
        UserError.Log.v(TAG,"j:" + j + ",SumSquareDifToWAVG:" +  SumSquareDifToWAVG + ", sigma:" + sigma);
        return sigma;
    }
    private static double calculateLibre2Noise(long startTimestamp, long endTimestamp) {
        double sigma = -1;
        double SumSquareDifToWAVG = 0;
        List<Libre2RawValue> RawValuesForNoise = Libre2RawValue.latestForGraph(100, startTimestamp, endTimestamp);
        List<BgReading> BgReadingsForMean = BgReading.latestForGraph(30, startTimestamp - (5*60*1000), endTimestamp + (5*60*1000));
        UserError.Log.v(TAG,"rawValuesForNoise size:" + RawValuesForNoise.size() + ", RawValuesForNoiseFirstTimestamp" + RawValuesForNoise.get(RawValuesForNoise.size()-1).timestamp + ", RawValuesForNoiseLastTimestamp" + RawValuesForNoise.get(0).timestamp);
        UserError.Log.v(TAG,"BgReadingsForMean Size:" + BgReadingsForMean.size() + ", BgReadingFirstTimestamp:" + BgReadingsForMean.get(BgReadingsForMean.size()-1).timestamp + ", BgReadingLastTimestamp:" + BgReadingsForMean.get(0).timestamp);
        if (BgReadingsForMean.size() == 0) {
            Log.e(TAG,"No BgReading 5 min around provided rawvlues. Can't calculate noise.");
            return -1;
        }
        int j=0;
        for (Libre2RawValue rawValue : RawValuesForNoise) {
            int i=0;
            double WAVGBgReadingValue=0;
            for (BgReading bgReading : BgReadingsForMean) {
                if (bgReading.timestamp <= rawValue.timestamp) {
                    break;
                }
                i++;
            }
            BgReading OlderBgReading = BgReadingsForMean.get(i);
            UserError.Log.v(TAG,"OlderBgReadingIndex:" + (i) + " for rawValue run " + j);
            if (i==0 & (Math.abs(rawValue.timestamp - BgReadingsForMean.get(i).timestamp) <= (5*60*1000))) {
                UserError.Log.d(TAG,"Calculating mean deviation based only on OlderBgReading as no Younger BgReading exists within 5min for rawValue run " + j);
                if (OlderBgReading.calibration != null) {
                    WAVGBgReadingValue = (OlderBgReading.calculated_value - OlderBgReading.calibration.intercept) / OlderBgReading.calibration.slope;
                }
                else {
                    WAVGBgReadingValue = OlderBgReading.calculated_value;
                }
            }
            else if (i==0){
                UserError.Log.d(TAG, "No BgReading younger and older within 5 min found for rawValue run " + j + ". Can't calculate noise for rawValue run " + j);
                break;
            }
            else {
                UserError.Log.d(TAG, "Calculating mean deviation based on OlderBgReading and YoungerBgReading for rawValue run " + j);

                if (OlderBgReading.timestamp == rawValue.timestamp) {
                    if (OlderBgReading.calibration != null) {
                        WAVGBgReadingValue = (OlderBgReading.calculated_value - OlderBgReading.calibration.intercept) / OlderBgReading.calibration.slope;
                    }
                    else {
                        WAVGBgReadingValue = OlderBgReading.calculated_value;
                    }
                }
                else {
                    double OlderBgReadingUnCalibratedValue = 0;
                    if (OlderBgReading.calibration != null) {
                        OlderBgReadingUnCalibratedValue = (OlderBgReading.calculated_value - OlderBgReading.calibration.intercept) / OlderBgReading.calibration.slope;
                    } else {
                        OlderBgReadingUnCalibratedValue = OlderBgReading.calculated_value;
                    }

                    BgReading YoungerBgReading = BgReadingsForMean.get(i-1);
                    double YoungerBgReadingUnCalibratedValue = 0;
                    if (OlderBgReading.calibration != null) {
                        YoungerBgReadingUnCalibratedValue = (YoungerBgReading.calculated_value - YoungerBgReading.calibration.intercept) / YoungerBgReading.calibration.slope;
                    } else {
                        YoungerBgReadingUnCalibratedValue = YoungerBgReading.calculated_value;
                    }
                    UserError.Log.v(TAG, "YoungerBgReadingUnCalibratedValue:" + YoungerBgReadingUnCalibratedValue + ", OlderBgReadingUnCalibratedValue:" + OlderBgReadingUnCalibratedValue);
                    double wOlderBgReading = 1 - ((double) Math.abs(OlderBgReading.timestamp - rawValue.timestamp)) / ((double) Math.abs( OlderBgReading.timestamp - YoungerBgReading.timestamp));
                    double wYoungerBgReading = 1 - ((double) Math.abs(YoungerBgReading.timestamp - rawValue.timestamp)) / ((double) Math.abs(OlderBgReading.timestamp - YoungerBgReading.timestamp));
                    UserError.Log.v(TAG, "wYoungerBgReading:" + wYoungerBgReading + ", wOlderBgReading:" + wOlderBgReading);
                    if (wYoungerBgReading == 0 || wOlderBgReading == 0) {
                        Log.e(TAG, "At least one of the BgReading weight is 0 on run " + i);
                        break;
                    }
                    WAVGBgReadingValue = (wOlderBgReading * OlderBgReadingUnCalibratedValue + wYoungerBgReading * YoungerBgReadingUnCalibratedValue) / (wOlderBgReading + wYoungerBgReading);
                }
            }

            SumSquareDifToWAVG += Math.pow(rawValue.glucose - WAVGBgReadingValue,2);
            UserError.Log.v(TAG,"rawValueNumber:" + j + ", WAVGBgReadingValue:" + WAVGBgReadingValue + ", rawValue.glucose:" + rawValue.glucose + ", SquareDifToWAVG:" + Math.pow(rawValue.glucose - WAVGBgReadingValue,2) + ", SumSquareDifToWAVG:" + SumSquareDifToWAVG);
            j++;
        }
        if (j != 0) {
            sigma = Math.sqrt(SumSquareDifToWAVG / j);
        }
        UserError.Log.d(TAG, "SumSquareDifToWAVG:" + SumSquareDifToWAVG + ", rawValuesUsed:" + j + ", sigma:" + sigma);
        return sigma;
    }
    */
    // End of Smoothing algos by Capflamme

    static class SortByGlucoseThenTimeInverted implements Comparator<Libre2RawValue> {
        public int compare(final Libre2RawValue rawValue1, final Libre2RawValue rawValue2) {
            int c;
            c = (int) (rawValue1.glucose - rawValue2.glucose);
            if (c == 0)
                c = (int) (rawValue2.timestamp - rawValue1.timestamp);
            return c;
        }
    }

    static class SortByGlucoseInvertedThenTimeInverted implements Comparator<Libre2RawValue> {
        public int compare(final Libre2RawValue rawValue1, final Libre2RawValue rawValue2) {
            int c;
            c = (int) (rawValue2.glucose - rawValue1.glucose);
            if (c == 0)
                c = (int) (rawValue2.timestamp - rawValue1.timestamp);
            return c;
        }
    }

    static class SortByTime implements Comparator<Libre2RawValue> {
        public int compare(final Libre2RawValue rawValue1, final Libre2RawValue rawValue2) {
           return (int) (rawValue1.timestamp - rawValue2.timestamp);
        }
    }

    public static List<StatusItem> megaStatus() {
        final List<StatusItem> l = new ArrayList<>();
        final Sensor sensor = Sensor.currentSensor();
        if (sensor != null) {
            l.add(new StatusItem("Libre2 Sensor", sensor.uuid + "\nStart: " + DateFormat.format("dd.MM.yyyy kk:mm", sensor.started_at)));
        }
        String lastReading ="";
        try {
            lastReading = DateFormat.format("dd.MM.yyyy kk:mm:ss", last_reading).toString();
            l.add(new StatusItem("Last Reading", lastReading));
        } catch (Exception e) {
            Log.e(TAG, "Error readlast: " + e);
        }
        if (get_engineering_mode()) {
            l.add(new StatusItem("Last Calc.", libre_calc_doku));
        }
        if (Pref.getBooleanDefaultFalse("Libre2_showSensors")) {
            l.add(new StatusItem("Sensors", Libre2Sensors()));
        }
        return l;
    }
}
