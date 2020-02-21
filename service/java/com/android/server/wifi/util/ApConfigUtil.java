/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.net.MacAddress;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApConfiguration.BandType;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.util.Log;

import com.android.server.wifi.WifiNative;
import com.android.wifi.resources.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Provide utility functions for updating soft AP related configuration.
 */
public class ApConfigUtil {
    private static final String TAG = "ApConfigUtil";

    public static final int DEFAULT_AP_BAND = SoftApConfiguration.BAND_2GHZ;
    public static final int DEFAULT_AP_CHANNEL = 6;
    public static final int HIGHEST_2G_AP_CHANNEL = 14;

    /* Return code for updateConfiguration. */
    public static final int SUCCESS = 0;
    public static final int ERROR_NO_CHANNEL = 1;
    public static final int ERROR_GENERIC = 2;
    public static final int ERROR_UNSUPPORTED_CONFIGURATION = 3;

    /* Random number generator used for AP channel selection. */
    private static final Random sRandom = new Random();

    /**
     * Convert channel/band to frequency.
     * Note: the utility does not perform any regulatory domain compliance.
     * @param channel number to convert
     * @param band of channel to convert
     * @return center frequency in Mhz of the channel, -1 if no match
     */
    public static int convertChannelToFrequency(int channel, @BandType int band) {
        if (band == SoftApConfiguration.BAND_2GHZ) {
            if (channel == 14) {
                return 2484;
            } else if (channel >= 1 && channel <= 14) {
                return ((channel - 1) * 5) + 2412;
            } else {
                return -1;
            }
        }
        if (band == SoftApConfiguration.BAND_5GHZ) {
            if (channel >= 34 && channel <= 173) {
                return ((channel - 34) * 5) + 5170;
            } else {
                return -1;
            }
        }
        if (band == SoftApConfiguration.BAND_6GHZ) {
            if (channel >= 1 && channel <= 254) {
                return (channel * 5) + 5940;
            } else {
                return -1;
            }
        }

        return -1;
    }

    /**
     * Convert frequency to channel.
     * Note: the utility does not perform any regulatory domain compliance.
     * @param frequency frequency to convert
     * @return channel number associated with given frequency, -1 if no match
     */
    public static int convertFrequencyToChannel(int frequency) {
        if (frequency >= 2412 && frequency <= 2472) {
            return (frequency - 2412) / 5 + 1;
        } else if (frequency == 2484) {
            return 14;
        } else if (frequency >= 5170  &&  frequency <= 5865) {
            /* DFS is included. */
            return (frequency - 5170) / 5 + 34;
        } else if (frequency > 5940  && frequency < 7210) {
            return ((frequency - 5940) / 5);
        }

        return -1;
    }

    /**
     * Convert frequency to band.
     * Note: the utility does not perform any regulatory domain compliance.
     * @param frequency frequency to convert
     * @return band, -1 if no match
     */
    public static int convertFrequencyToBand(int frequency) {
        if (frequency >= 2412 && frequency <= 2484) {
            return SoftApConfiguration.BAND_2GHZ;
        } else if (frequency >= 5170  &&  frequency <= 5865) {
            return SoftApConfiguration.BAND_5GHZ;
        } else if (frequency > 5940  && frequency < 7210) {
            return SoftApConfiguration.BAND_6GHZ;
        }

        return -1;
    }

    /**
     * Convert band from WifiConfiguration into SoftApConfiguration
     *
     * @param wifiConfigBand band encoded as WifiConfiguration.AP_BAND_xxxx
     * @return band as encoded as SoftApConfiguration.BAND_xxx
     */
    public static int convertWifiConfigBandToSoftApConfigBand(int wifiConfigBand) {
        switch (wifiConfigBand) {
            case WifiConfiguration.AP_BAND_2GHZ:
                return SoftApConfiguration.BAND_2GHZ;
            case WifiConfiguration.AP_BAND_5GHZ:
                return SoftApConfiguration.BAND_5GHZ;
            case WifiConfiguration.AP_BAND_ANY:
                return SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ;
            default:
                return -1;
        }
    }

    /**
     * Checks if band is a valid combination of {link  SoftApConfiguration#BandType} values
     */
    public static boolean isBandValid(@BandType int band) {
        return ((band != 0) && ((band & ~SoftApConfiguration.BAND_ANY) == 0));
    }

    /**
     * Check if the band contains a certain sub-band
     *
     * @param band The combination of bands to validate
     * @param testBand the test band to validate on
     * @return true if band contains testBand, false otherwise
     */
    public static boolean containsBand(@BandType int band, @BandType int testBand) {
        return ((band & testBand) != 0);
    }

    /**
     * Checks if band contains multiple sub-bands
     * @param band a combination of sub-bands
     * @return true if band has multiple sub-bands, false otherwise
     */
    public static boolean isMultiband(@BandType int band) {
        return ((band & (band - 1)) != 0);
    }

    /**
     * Convert string to channel list
     * Format of the list is a comma separated channel numbers, or range of channel numbers
     * Example, "34-48, 149".
     * @param channelString for a comma separated channel numbers, or range of channel numbers
     *        such as "34-48, 149"
     * @return list of channel numbers
     */
    public static List<Integer> convertStringToChannelList(String channelString) {
        if (channelString == null) {
            return null;
        }

        List<Integer> channelList = new ArrayList<Integer>();

        for (String channelRange : channelString.split(",")) {
            try {
                if (channelRange.contains("-")) {
                    String[] channels = channelRange.split("-");
                    if (channels.length != 2) {
                        Log.e(TAG, "Unrecognized channel range, Length is " + channels.length);
                        continue;
                    }
                    int start = Integer.parseInt(channels[0].trim());
                    int end = Integer.parseInt(channels[1].trim());
                    if (start > end) {
                        Log.e(TAG, "Invalid channel range, from " + start + " to " + end);
                        continue;
                    }

                    for (int channel = start; channel <= end; channel++) {
                        channelList.add(channel);
                    }
                } else {
                    channelList.add(Integer.parseInt(channelRange.trim()));
                }
            } catch (NumberFormatException e) {
                // Ignore malformed string
                Log.e(TAG, "Malformed channel value detected: " + e);
                continue;
            }
        }
        return channelList;
    }

    /**
     * Get channel frequencies for band that are allowed by both regulatory and OEM configuration
     *
     * @param band to get channels for
     * @param wifiNative reference used to get regulatory restrictionsimport java.util.Arrays;
     * @param resources used to get OEM restrictions
     * @return A list of frequencies that are allowed, null on error.
     */
    public static List<Integer> getAvailableChannelFreqsForBand(
            @BandType int band, WifiNative wifiNative, Resources resources) {
        if (!isBandValid(band) || isMultiband(band)) {
            return null;
        }

        List<Integer> configuredList;
        int scannerBand;
        switch (band) {
            case SoftApConfiguration.BAND_2GHZ:
                configuredList = convertStringToChannelList(resources.getString(
                        R.string.config_wifiSoftap2gChannelList));
                scannerBand = WifiScanner.WIFI_BAND_24_GHZ;
                break;
            case SoftApConfiguration.BAND_5GHZ:
                configuredList = convertStringToChannelList(resources.getString(
                        R.string.config_wifiSoftap5gChannelList));
                scannerBand = WifiScanner.WIFI_BAND_5_GHZ;
                break;
            case SoftApConfiguration.BAND_6GHZ:
                configuredList = convertStringToChannelList(resources.getString(
                        R.string.config_wifiSoftap6gChannelList));
                scannerBand = WifiScanner.WIFI_BAND_6_GHZ;
                break;
            default:
                return null;
        }

        // Get the allowed list of channel frequencies in MHz
        int[] regulatoryArray = wifiNative.getChannelsForBand(scannerBand);
        List<Integer> regulatoryList = new ArrayList<Integer>();
        for (int freq : regulatoryArray) {
            regulatoryList.add(freq);
        }

        if (configuredList == null || configuredList.isEmpty()) {
            return regulatoryList;
        }

        List<Integer> filteredList = new ArrayList<Integer>();
        // Otherwise, filter the configured list
        for (int channel : configuredList) {
            int channelFreq = convertChannelToFrequency(channel, band);

            if (regulatoryList.contains(channelFreq)) {
                filteredList.add(channelFreq);
            }
        }
        return filteredList;
    }

    /**
     * Return a channel number for AP setup based on the frequency band.
     * @param apBand one or combination of the values of SoftApConfiguration.BAND_*.
     * @param wifiNative reference used to collect regulatory restrictions.
     * @param resources the resources to use to get configured allowed channels.
     * @return a valid channel frequency on success, -1 on failure.
     */
    public static int chooseApChannel(int apBand, WifiNative wifiNative, Resources resources) {
        if (!isBandValid(apBand)) {
            Log.e(TAG, "Invalid band: " + apBand);
            return -1;
        }

        int totalChannelCount = 0;
        int size2gList = 0;
        int size5gList = 0;
        int size6gList = 0;
        List<Integer> allowed2gFreqList = null;
        List<Integer> allowed5gFreqList = null;
        List<Integer> allowed6gFreqList = null;

        if ((apBand & SoftApConfiguration.BAND_2GHZ) != 0) {
            allowed2gFreqList = getAvailableChannelFreqsForBand(SoftApConfiguration.BAND_2GHZ,
                    wifiNative, resources);
            if (allowed2gFreqList != null) {
                size2gList = allowed2gFreqList.size();
                totalChannelCount += size2gList;
            }
        }
        if ((apBand & SoftApConfiguration.BAND_5GHZ) != 0) {
            allowed5gFreqList = getAvailableChannelFreqsForBand(SoftApConfiguration.BAND_5GHZ,
                    wifiNative, resources);
            if (allowed5gFreqList != null) {
                size5gList = allowed5gFreqList.size();
                totalChannelCount += size5gList;
            }
        }
        if ((apBand & SoftApConfiguration.BAND_6GHZ) != 0) {
            allowed6gFreqList = getAvailableChannelFreqsForBand(SoftApConfiguration.BAND_6GHZ,
                    wifiNative, resources);
            if (allowed6gFreqList != null) {
                size6gList = allowed6gFreqList.size();
                totalChannelCount += size6gList;
            }
        }

        if (totalChannelCount == 0) {
            // If the default AP band is allowed, just use the default channel
            if (containsBand(apBand, DEFAULT_AP_BAND)) {
                Log.d(TAG, "Allowed channel list not specified, selecting default channel");
                /* Use default channel. */
                return convertChannelToFrequency(DEFAULT_AP_CHANNEL,
                        DEFAULT_AP_BAND);
            } else {
                Log.e(TAG, "No available channels");
                return -1;
            }
        }

        // Pick a channel
        int selectedChannelIndex = sRandom.nextInt(totalChannelCount);

        if (size2gList != 0) {
            if (selectedChannelIndex < size2gList) {
                return allowed2gFreqList.get(selectedChannelIndex).intValue();
            } else {
                selectedChannelIndex -= size2gList;
            }
        }

        if (size5gList != 0) {
            if (selectedChannelIndex < size5gList) {
                return allowed5gFreqList.get(selectedChannelIndex).intValue();
            } else {
                selectedChannelIndex -= size5gList;
            }
        }

        return allowed6gFreqList.get(selectedChannelIndex).intValue();
    }

    /**
     * Update AP band and channel based on the provided country code and band.
     * This will also set
     * @param wifiNative reference to WifiNative
     * @param resources the resources to use to get configured allowed channels.
     * @param countryCode country code
     * @param config configuration to update
     * @return an integer result code
     */
    public static int updateApChannelConfig(WifiNative wifiNative,
                                            Resources resources,
                                            String countryCode,
                                            SoftApConfiguration.Builder configBuilder,
                                            SoftApConfiguration config,
                                            boolean acsEnabled) {
        /* Use default band and channel for device without HAL. */
        if (!wifiNative.isHalStarted()) {
            configBuilder.setChannel(DEFAULT_AP_CHANNEL, DEFAULT_AP_BAND);
            return SUCCESS;
        }

        /* Country code is mandatory for 5GHz band. */
        if (config.getBand() == SoftApConfiguration.BAND_5GHZ
                && countryCode == null) {
            Log.e(TAG, "5GHz band is not allowed without country code");
            return ERROR_GENERIC;
        }

        /* Select a channel if it is not specified and ACS is not enabled */
        if ((config.getChannel() == 0) && !acsEnabled) {
            int freq = chooseApChannel(config.getBand(), wifiNative, resources);
            if (freq == -1) {
                /* We're not able to get channel from wificond. */
                Log.e(TAG, "Failed to get available channel.");
                return ERROR_NO_CHANNEL;
            }
            configBuilder.setChannel(
                    convertFrequencyToChannel(freq), convertFrequencyToBand(freq));
        }

        return SUCCESS;
    }

    /**
     * Helper function for converting WifiConfiguration to SoftApConfiguration.
     *
     * Only Support None and WPA2 configuration conversion.
     * Note that WifiConfiguration only Supports 2GHz, 5GHz, 2GHz+5GHz bands,
     * so conversion is limited to these bands.
     *
     * @param wifiConfig the WifiConfiguration which need to convert.
     * @return the SoftApConfiguration if wifiConfig is valid, null otherwise.
     */
    @Nullable
    public static SoftApConfiguration fromWifiConfiguration(
            @NonNull WifiConfiguration wifiConfig) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        try {
            configBuilder.setSsid(wifiConfig.SSID);
            if (wifiConfig.BSSID != null) {
                configBuilder.setBssid(MacAddress.fromString(wifiConfig.BSSID));
            }
            if (wifiConfig.getAuthType() == WifiConfiguration.KeyMgmt.WPA2_PSK) {
                configBuilder.setPassphrase(wifiConfig.preSharedKey,
                        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            }
            configBuilder.setHiddenSsid(wifiConfig.hiddenSSID);

            int band;
            switch (wifiConfig.apBand) {
                case WifiConfiguration.AP_BAND_2GHZ:
                    band = SoftApConfiguration.BAND_2GHZ;
                    break;
                case WifiConfiguration.AP_BAND_5GHZ:
                    band = SoftApConfiguration.BAND_5GHZ;
                    break;
                default:
                    // WifiConfiguration.AP_BAND_ANY means only 2GHz and 5GHz bands
                    band = SoftApConfiguration.BAND_2GHZ | SoftApConfiguration.BAND_5GHZ;
                    break;
            }
            if (wifiConfig.apChannel == 0) {
                configBuilder.setBand(band);
            } else {
                configBuilder.setChannel(wifiConfig.apChannel, band);
            }
        } catch (IllegalArgumentException iae) {
            Log.e(TAG, "Invalid WifiConfiguration" + iae);
            return null;
        } catch (IllegalStateException ise) {
            Log.e(TAG, "Invalid WifiConfiguration" + ise);
            return null;
        }
        return configBuilder.build();
    }

    /**
     * Helper function to creating SoftApCapability instance with initial field from resource file.
     *
     * @param context the caller context used to get value from resource file.
     * @return SoftApCapability which updated the feature support or not from resource.
     */
    @NonNull
    public static SoftApCapability updateCapabilityFromResource(@NonNull Context context) {
        long features = 0;
        if (isAcsSupported(context)) {
            Log.d(TAG, "Update Softap capability, add acs feature support");
            features |= SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD;
        }

        if (isClientForceDisconnectSupported(context)) {
            Log.d(TAG, "Update Softap capability, add client control feature support");
            features |= SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT;
        }

        if (isWpa3SaeSupported(context)) {
            Log.d(TAG, "Update Softap capability, add SAE feature support");
            features |= SoftApCapability.SOFTAP_FEATURE_WPA3_SAE;
        }
        SoftApCapability capability = new SoftApCapability(features);
        int hardwareSupportedMaxClient = context.getResources().getInteger(
                R.integer.config_wifiHardwareSoftapMaxClientCount);
        if (hardwareSupportedMaxClient > 0) {
            Log.d(TAG, "Update Softap capability, max client = " + hardwareSupportedMaxClient);
            capability.setMaxSupportedClients(hardwareSupportedMaxClient);
        }

        return capability;
    }

    /**
     * Helper function to get hal support client force disconnect or not.
     *
     * @param context the caller context used to get value from resource file.
     * @return true if supported, false otherwise.
     */
    public static boolean isClientForceDisconnectSupported(@NonNull Context context) {
        return context.getResources().getBoolean(
                R.bool.config_wifiSofapClientForceDisconnectSupported);
    }

    /**
     * Helper function to get SAE support or not.
     *
     * @param context the caller context used to get value from resource file.
     * @return true if supported, false otherwise.
     */
    public static boolean isWpa3SaeSupported(@NonNull Context context) {
        return context.getResources().getBoolean(
                R.bool.config_wifi_softap_sae_supported);
    }

    /**
     * Helper function to get ACS support or not.
     *
     * @param context the caller context used to get value from resource file.
     * @return true if supported, false otherwise.
     */
    public static boolean isAcsSupported(@NonNull Context context) {
        return context.getResources().getBoolean(
                R.bool.config_wifi_softap_acs_supported);
    }

    /**
     * Helper function for comparing two SoftApConfiguration.
     *
     * @param currentConfig the original configuration.
     * @param newConfig the new configuration which plan to apply.
     * @return true if the difference between the two configurations requires a restart to apply,
     *         false otherwise.
     */
    public static boolean checkConfigurationChangeNeedToRestart(
            SoftApConfiguration currentConfig, SoftApConfiguration newConfig) {
        return !Objects.equals(currentConfig.getSsid(), newConfig.getSsid())
                || !Objects.equals(currentConfig.getBssid(), newConfig.getBssid())
                || currentConfig.getSecurityType() != newConfig.getSecurityType()
                || !Objects.equals(currentConfig.getPassphrase(), newConfig.getPassphrase())
                || currentConfig.isHiddenSsid() != newConfig.isHiddenSsid()
                || currentConfig.getBand() != newConfig.getBand()
                || currentConfig.getChannel() != newConfig.getChannel();
    }


    /**
     * Helper function for checking all of the configuration are supported or not.
     *
     * @param config target configuration want to check.
     * @param capability the capability which indicate feature support or not.
     * @return true if supported, false otherwise.
     */
    public static boolean checkSupportAllConfiguration(SoftApConfiguration config,
            SoftApCapability capability) {
        if (!capability.isFeatureSupported(
                SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT)
                && (config.getMaxNumberOfClients() != 0 || config.isClientControlByUserEnabled())) {
            Log.d(TAG, "Error, Client control requires HAL support");
            return false;
        }

        if (!capability.isFeatureSupported(SoftApCapability.SOFTAP_FEATURE_WPA3_SAE)
                && (config.getSecurityType()
                == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION
                || config.getSecurityType() == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)) {
            Log.d(TAG, "Error, SAE requires HAL support");
            return false;
        }
        return true;
    }
}
