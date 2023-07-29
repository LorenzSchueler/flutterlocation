package com.lyokone.location.location.providers.locationprovider;

import android.app.Dialog;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.lyokone.location.location.constants.FailType;
import com.lyokone.location.location.constants.ProcessType;
import com.lyokone.location.location.constants.RequestCode;
import com.lyokone.location.location.helper.LogUtils;
import com.lyokone.location.location.helper.continuoustask.ContinuousTask.ContinuousTaskRunner;
import com.lyokone.location.location.listener.DialogListener;
import com.lyokone.location.location.providers.dialogprovider.DialogProvider;

@SuppressWarnings("ResourceType")
public class DefaultLocationProvider extends LocationProvider
        implements ContinuousTaskRunner, LocationListener, DialogListener {

    private com.lyokone.location.location.providers.locationprovider.DefaultLocationSource defaultLocationSource;

    private String provider;
    private Dialog gpsDialog;

    @Override
    public void onDestroy() {
        super.onDestroy();

        gpsDialog = null;

        getSourceProvider().removeSwitchTask();
        getSourceProvider().removeUpdateRequest();
        getSourceProvider().removeLocationUpdates(this);
    }

    @Override
    public void cancel() {
        getSourceProvider().getUpdateRequest().release();
        getSourceProvider().getProviderSwitchTask().stop();
    }

    @Override
    public void onPause() {
        super.onPause();

        getSourceProvider().getUpdateRequest().release();
        getSourceProvider().getProviderSwitchTask().pause();
    }

    @Override
    public void onResume() {
        super.onResume();

        getSourceProvider().getUpdateRequest().run();

        if (isWaiting()) {
            getSourceProvider().getProviderSwitchTask().resume();
        }

        if (isDialogShowing() && isGPSProviderEnabled()) {
            // User activated GPS by going settings manually
            gpsDialog.dismiss();
            onGPSActivated();
        }
    }

    @Override
    public boolean isDialogShowing() {
        return gpsDialog != null && gpsDialog.isShowing();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RequestCode.GPS_ENABLE) {
            if (isGPSProviderEnabled()) {
                onGPSActivated();
            } else {
                onLocationFailed(FailType.PERMISSION_DENIED);
            }
        }
    }

    @Override
    public void get() {
        setWaiting(true);

        // First check for GPS
        if (isGPSProviderEnabled()) {
            LogUtils.logI("GPS is already enabled, getting location...");
            askForLocation(LocationManager.GPS_PROVIDER);
        } else {
            // GPS is not enabled,
            if (getConfiguration().defaultProviderConfiguration().askForEnableGPS() && getActivity() != null) {
                LogUtils.logI("GPS is not enabled, asking user to enable it...");
                askForEnableGPS();
            } else {
                onLocationFailed(FailType.PERMISSION_DENIED);
            }
        }
    }

    void askForEnableGPS() {
        DialogProvider gpsDialogProvider = getConfiguration().defaultProviderConfiguration().gpsDialogProvider();
        gpsDialogProvider.setDialogListener(this);

        gpsDialog = gpsDialogProvider.getDialog(getActivity());
        gpsDialog.show();
    }

    void onGPSActivated() {
        LogUtils.logI("User activated GPS, listen for location");
        askForLocation(LocationManager.GPS_PROVIDER);
    }

    void askForLocation(String provider) {
        setCurrentProvider(provider);

        boolean locationIsAlreadyAvailable = checkForLastKnowLocation();

        if (getConfiguration().keepTracking() || !locationIsAlreadyAvailable) {
            LogUtils.logI("Ask for location update...");
            notifyProcessChange();

            requestUpdateLocation();
        } else {
            LogUtils.logI("We got location, no need to ask for location updates.");
        }
    }

    boolean checkForLastKnowLocation() {
        Location lastKnownLocation = getSourceProvider().getLastKnownLocation(provider);

        if (getSourceProvider().isLocationSufficient(lastKnownLocation,
                getConfiguration().defaultProviderConfiguration().acceptableTimePeriod(),
                getConfiguration().defaultProviderConfiguration().acceptableAccuracy())) {
            LogUtils.logI("LastKnowLocation is usable.");
            onLocationReceived(lastKnownLocation);
            return true;
        } else {
            LogUtils.logI("LastKnowLocation is not usable.");
            return false;
        }
    }

    void setCurrentProvider(String provider) {
        this.provider = provider;
    }

    void notifyProcessChange() {
        if (getListener() != null) {
            getListener().onProcessTypeChanged(LocationManager.GPS_PROVIDER.equals(provider)
                    ? ProcessType.GETTING_LOCATION_FROM_GPS_PROVIDER
                    : ProcessType.GETTING_LOCATION_FROM_NETWORK_PROVIDER);
        }
    }

    void requestUpdateLocation() {
        long timeInterval = getConfiguration().defaultProviderConfiguration().requiredTimeInterval();
        long distanceInterval = getConfiguration().defaultProviderConfiguration().requiredDistanceInterval();
        getSourceProvider().getUpdateRequest().run(provider, timeInterval, distanceInterval);
    }

    public boolean isNetworkProviderEnabled() {
        return getSourceProvider().isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    public boolean isGPSProviderEnabled() {
        return getSourceProvider().isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    void onLocationReceived(Location location) {
        if (getListener() != null) {
            getListener().onLocationChanged(location);
        }
        setWaiting(false);
    }

    void onLocationFailed(@FailType int type) {
        if (getListener() != null) {
            getListener().onLocationFailed(type);
        }
        setWaiting(false);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (getSourceProvider().updateRequestIsRemoved()) {
            return;
        }

        onLocationReceived(location);

        if (!getConfiguration().keepTracking()) {
            getSourceProvider().getUpdateRequest().release();
            getSourceProvider().removeLocationUpdates(this);
        }
    }

    /**
     * This callback will never be invoked on Android Q and above, and providers can be considered as always in the LocationProvider#AVAILABLE state.
     *
     * @see <a href="https://developer.android.com/reference/android/location/LocationListener#onStatusChanged(java.lang.String,%20int,%20android.os.Bundle)">LocationListener#onStatusChanged</a>
     */
    @Deprecated
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (getListener() != null) {
            getListener().onStatusChanged(provider, status, extras);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (getListener() != null) {
            getListener().onProviderEnabled(provider);
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (getListener() != null) {
            getListener().onProviderDisabled(provider);
        }
    }

    @Override
    public void runScheduledTask(@NonNull String taskId) {
        // never switch provider or timeout; ignore task
    }

    @Override
    public void onPositiveButtonClick() {
        boolean activityStarted = startActivityForResult(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        );
        if (!activityStarted) {
            onLocationFailed(FailType.VIEW_NOT_REQUIRED_TYPE);
        }
    }

    @Override
    public void onNegativeButtonClick() {
        LogUtils.logI("User did not enable GPS");
        onLocationFailed(FailType.PERMISSION_DENIED);
    }

    // For test purposes
    public void setDefaultLocationSource(com.lyokone.location.location.providers.locationprovider.DefaultLocationSource defaultLocationSource) {
        this.defaultLocationSource = defaultLocationSource;
    }

    private com.lyokone.location.location.providers.locationprovider.DefaultLocationSource getSourceProvider() {
        if (defaultLocationSource == null) {
            defaultLocationSource = new com.lyokone.location.location.providers.locationprovider.DefaultLocationSource(getContext(), this, this);
        }
        return defaultLocationSource;
    }

}