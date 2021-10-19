package com.google.private_retrieval.pir;

import android.Manifest.permission;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import androidx.core.content.ContextCompat;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Android-centric implementation of PirDownloadConstraintsManager supporting network connectivity
 * constraints.
 *
 * <p>Typical usage: (1) Construct a singleton AndroidPirDownloadConstraintsManager for your app.
 * (2) Use AndroidPirDownloadConstraintsManager.constraintsBuilder() to generate constraints and use
 * those constraints to construct PirDownloadTasks. (3) Submit the PirDownloadTasks to a
 * PirDownloadExecutor, which will schedule them for execution as soon as their download constraints
 * are satisfied.
 */
public class AndroidPirDownloadConstraintsManager implements PirDownloadConstraintsManager {

  /**
   * PirDownloadConstraints that can be managed by AndroidPirDownloadConstraintsManager. Currently
   * supports network connectivity constraints.
   */
  public static class DownloadConstraints implements PirDownloadConstraints {

    private final AndroidPirDownloadConstraintsManager manager;
    private final BitSet requiredNetworkCapabilities;
    private final BitSet unwantedNetworkCapabilities;
    private final BitSet acceptableNetworkTransports;

    public static Builder builder() {
      return new DownloadConstraints.Builder();
    }

    private DownloadConstraints(Builder builder) {
      manager = Preconditions.checkNotNull(builder.manager);
      this.requiredNetworkCapabilities = new BitSet(builder.requiredNetworkCapabilities.size());
      this.requiredNetworkCapabilities.or(builder.requiredNetworkCapabilities);

      this.unwantedNetworkCapabilities = new BitSet(builder.unwantedNetworkCapabilities.size());
      this.unwantedNetworkCapabilities.or(builder.unwantedNetworkCapabilities);

      this.acceptableNetworkTransports = new BitSet(builder.acceptableNetworkTransports.size());
      this.acceptableNetworkTransports.or(builder.acceptableNetworkTransports);
    }

    /** Builder for AndroidPirDownloadConstraintsManager.DownloadConstraints instances. */
    public static class Builder {

      private @Nullable AndroidPirDownloadConstraintsManager manager;
      private final BitSet requiredNetworkCapabilities = new BitSet();
      private final BitSet unwantedNetworkCapabilities = new BitSet();
      private final BitSet acceptableNetworkTransports = new BitSet();

      private Builder() {}

      public Builder setManager(AndroidPirDownloadConstraintsManager manager) {
        this.manager = manager;
        return this;
      }

      /**
       * Add the given capability requirement to this builder. These represent the requested
       * network's required capabilities. Note all capabilities requested must be satisfied in order
       * to satisfy the download constraints.
       *
       * <p>If the capability was previously added to the list of unwanted capabilities, then it
       * will be removed from the list of unwanted capabilities as well.
       *
       * @param capability The capability to add; one of NetworkCapabilities.NET_CAPABILITY_*.
       */
      public Builder addRequiredNetworkCapability(int capability) {
        this.requiredNetworkCapabilities.set(capability);
        this.unwantedNetworkCapabilities.clear(capability);
        return this;
      }

      /**
       * Removes (if found) the given required capability from this builder instance.
       *
       * @param capability The capability to remove; one of NetworkCapabilities.NET_CAPABILITY_*.
       */
      public Builder removeRequiredNetworkCapability(int capability) {
        this.requiredNetworkCapabilities.clear(capability);
        return this;
      }

      /**
       * Completely clears all the required {@code NetworkCapabilities} from this builder instance.
       */
      public Builder clearRequiredNetworkCapabilities() {
        this.requiredNetworkCapabilities.clear();
        return this;
      }

      /**
       * Add a capability that must not exist in the requested network.
       *
       * <p>If the capability was previously added to the list of required capabilities, then it
       * will be removed from the list of required capabilities as well.
       *
       * @param capability The capability to add; one of NetworkCapabilities.NET_CAPABILITY_*.
       */
      public Builder addUnwantedNetworkCapability(int capability) {
        this.unwantedNetworkCapabilities.set(capability);
        this.requiredNetworkCapabilities.clear(capability);
        return this;
      }

      /**
       * Removes (if found) the given unwanted capability from this builder instance.
       *
       * @param capability The capability to remove; one of NetworkCapabilities.NET_CAPABILITY_*.
       */
      public Builder removeUnwantedNetworkCapability(int capability) {
        this.unwantedNetworkCapabilities.clear(capability);
        return this;
      }

      /**
       * Completely clears all the unwanted {@code NetworkCapabilities} from this builder instance.
       */
      public Builder clearUnwantedNetworkCapabilities() {
        this.unwantedNetworkCapabilities.clear();
        return this;
      }

      /**
       * Adds the given transport requirement to this builder. These represent the set of allowed
       * transports: only networks using one of these transports will satisfy the constraints.
       *
       * <p>If no acceptable transports are added, any transport will be considered acceptable.
       *
       * @param transport The transport type to add; one of NetworkCapabilities.TRANSPORT_*.
       */
      public Builder addAcceptableNetworkTransport(int transport) {
        this.acceptableNetworkTransports.set(transport);
        return this;
      }

      /**
       * Removes (if found) the given transport from this builder instance.
       *
       * @param transport The transport type to remove; one of NetworkCapabilities.TRANSPORT_*.
       * @return The builder to facilitate chaining.
       */
      public Builder removeAcceptableNetworkTransport(int transport) {
        this.acceptableNetworkTransports.clear(transport);
        return this;
      }

      /** Completely clears all the acceptable network transports from this builder instance. */
      public Builder clearAcceptableNetworkTransports() {
        this.acceptableNetworkTransports.clear();
        return this;
      }

      public DownloadConstraints build() {
        return new DownloadConstraints(this);
      }
    }

    @Override
    public boolean areSatisfied() {
      return manager.areNetworkCapabilitiesSatisfied(
          requiredNetworkCapabilities, unwantedNetworkCapabilities, acceptableNetworkTransports);
    }

    /**
     * The required NetworkCapabilities for these DownloadConstraints.
     *
     * <p>The network must have all of these capabilities to satisfy the constraints. Entries are
     * from NetworkCapabilities.NET_CAPABILITY_*.
     */
    public BitSet getRequiredNetworkCapabilities() {
      BitSet copy = new BitSet(requiredNetworkCapabilities.size());
      copy.or(requiredNetworkCapabilities);
      return copy;
    }

    /**
     * The required NetworkCapabilities for these DownloadConstraints.
     *
     * <p>The network must have none of these capabilities to satisfy the constraints. Entries are
     * from NetworkCapabilities.NET_CAPABILITY_*.
     */
    public BitSet getUnwantedNetworkCapabilities() {
      BitSet copy = new BitSet(unwantedNetworkCapabilities.size());
      copy.or(unwantedNetworkCapabilities);
      return copy;
    }

    /**
     * The acceptable network transports for these DownloadConstraints.
     *
     * <p>The network must use one of the acceptable network transports, or else the acceptable
     * transports list must be empty, in order to satisfy the constraints. Entries are from
     * NetworkCapabilities.TRANSPORT_*.
     */
    public BitSet getAcceptableNetworkTransports() {
      BitSet copy = new BitSet(acceptableNetworkTransports.size());
      copy.or(acceptableNetworkTransports);
      return copy;
    }

    @Override
    public PirDownloadConstraintsManager getManager() {
      return manager;
    }
  }

  private final Context context;
  private final ConnectivityManager connectivityManager;
  private final List<Listener> listeners = new ArrayList<>();

  public AndroidPirDownloadConstraintsManager(Context context) {
    this.context = context;
    this.connectivityManager =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  /** Create a DownloadConstraints Builder associated with this manager. */
  public DownloadConstraints.Builder constraintsBuilder() {
    return DownloadConstraints.builder().setManager(this);
  }

  private final BroadcastReceiver connectivityReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          String action = intent.getAction();
          if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
            synchronized (listeners) {
              for (Listener listener : listeners) {
                listener.onConstraintsMayHaveChanged();
              }
            }
          }
        }
      };

  @Override
  public void registerConstraintsChangeListener(Listener listener) {
    synchronized (listeners) {
      if (listeners.isEmpty()) {
        context.registerReceiver(
            connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
      }
      listeners.add(listener);
    }
  }

  @Override
  public void unregisterConstraintsChangeListener(Listener listener) {
    synchronized (listeners) {
      listeners.remove(listener);
      if (listeners.isEmpty()) {
        context.unregisterReceiver(connectivityReceiver);
      }
    }
  }

  private boolean areNetworkCapabilitiesSatisfied(
      BitSet requiredNetworkCapabilities,
      BitSet unwantedNetworkCapabilities,
      BitSet acceptableNetworkTransports) {
    // If all of the network constraints are empty, then we know these constraints are satisfied
    // without needing to use the ACCESS_NETWORK_STATE permission.
    if (requiredNetworkCapabilities.isEmpty()
        && unwantedNetworkCapabilities.isEmpty()
        && acceptableNetworkTransports.isEmpty()) {
      return true;
    }

    if (!isPermissionGranted(context, permission.ACCESS_NETWORK_STATE)) {
      throw new IllegalStateException(
          "Attempting to determine connectivity without the ACCESS_NETWORK_STATE permission.");
    }

    Network network = connectivityManager.getActiveNetwork();
    if (network == null) {
      return false;
    }

    NetworkCapabilities networkCapabilities = connectivityManager.getNetworkCapabilities(network);
    if (networkCapabilities == null) {
      return false;
    }

    for (int requiredNetworkCapability = requiredNetworkCapabilities.nextSetBit(0);
        requiredNetworkCapability >= 0;
        requiredNetworkCapability =
            requiredNetworkCapabilities.nextSetBit(requiredNetworkCapability + 1)) {
      if (!networkCapabilities.hasCapability(requiredNetworkCapability)) {
        return false;
      }
    }

    for (int unwantedNetworkCapability = unwantedNetworkCapabilities.nextSetBit(0);
        unwantedNetworkCapability >= 0;
        unwantedNetworkCapability =
            unwantedNetworkCapabilities.nextSetBit(unwantedNetworkCapability + 1)) {
      if (networkCapabilities.hasCapability(unwantedNetworkCapability)) {
        return false;
      }
    }

    if (acceptableNetworkTransports.isEmpty()) {
      return true;
    }
    for (int acceptableNetworkTransport = acceptableNetworkTransports.nextSetBit(0);
        acceptableNetworkTransport >= 0;
        acceptableNetworkTransport =
            acceptableNetworkTransports.nextSetBit(acceptableNetworkTransport + 1)) {
      if (networkCapabilities.hasTransport(acceptableNetworkTransport)) {
        return true;
      }
    }

    return false;
  }

  private static boolean isPermissionGranted(Context context, String permission) {
    return PackageManager.PERMISSION_GRANTED
        == ContextCompat.checkSelfPermission(context, permission);
  }
}
