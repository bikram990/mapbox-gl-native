package com.mapbox.mapboxsdk.style.sources;

import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;

import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.mapboxsdk.geometry.LatLngBounds;
import com.mapbox.mapboxsdk.style.expressions.Expression;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom Vector Source, allows using FeatureCollections.
 */
public class CustomGeometrySource extends Source {
  private final ExecutorService executor;
  private final GeometryTileProvider provider;
  private final Map<TileID, AtomicBoolean> cancelledTileRequests = new ConcurrentHashMap<>();
  private final GeometryTileRequestsQueue queue = new GeometryTileRequestsQueue();

  /**
   * Create a CustomGeometrySource
   *
   * @param id       The source id.
   * @param provider The tile provider that returns geometry data for this source.
   */
  @UiThread
  public CustomGeometrySource(String id, GeometryTileProvider provider) {
    this(id, provider, new CustomGeometrySourceOptions());
  }

  /**
   * Create a CustomGeometrySource with non-default CustomGeometrySourceOptions.
   * <p>Supported options are minZoom, maxZoom, buffer, and tolerance.</p>
   *
   * @param id       The source id.
   * @param provider The tile provider that returns geometry data for this source.
   * @param options  CustomGeometrySourceOptions.
   */
  @UiThread
  public CustomGeometrySource(String id, GeometryTileProvider provider, CustomGeometrySourceOptions options) {
    super();
    this.provider = provider;
    executor = new ThreadPoolExecutor(4, 4,
      0L, TimeUnit.MILLISECONDS, queue);
    initialize(id, options);
  }

  /**
   * Invalidate previously provided features within a given bounds at all zoom levels.
   * Invoking this method will result in new requests to `GeometryTileProvider` for regions
   * that contain, include, or intersect with the provided bounds.
   *
   * @param bounds The region in which features should be invalidated at all zoom levels
   */
  public void invalidateRegion(LatLngBounds bounds) {
    nativeInvalidateBounds(bounds);
  }

  /**
   * Invalidate the geometry contents of a specific tile. Invoking this method will result
   * in new requests to `GeometryTileProvider` for visible tiles.
   *
   * @param zoomLevel Tile zoom level.
   * @param x         Tile X coordinate.
   * @param y         Tile Y coordinate.
   */
  public void invalidateTile(int zoomLevel, int x, int y) {
    nativeInvalidateTile(zoomLevel, x, y);
  }

  /**
   * Set or update geometry contents of a specific tile. Use this method to update tiles
   * for which `GeometryTileProvider` was previously invoked. This method can be called from
   * background threads.
   *
   * @param zoomLevel Tile zoom level.
   * @param x         Tile X coordinate.
   * @param y         Tile Y coordinate.
   * @param data      Feature collection for the tile.
   */
  public void setTileData(int zoomLevel, int x, int y, FeatureCollection data) {
    nativeSetTileData(zoomLevel, x, y, data);
  }

  /**
   * Queries the source for features.
   *
   * @param filter an optional filter expression to filter the returned Features
   * @return the features
   */
  @NonNull
  public List<Feature> querySourceFeatures(@Nullable Expression filter) {
    Feature[] features = querySourceFeatures(filter != null ? filter.toArray() : null);
    return features != null ? Arrays.asList(features) : new ArrayList<Feature>();
  }

  @Keep
  protected native void initialize(String sourceId, Object options);

  @Keep
  private native Feature[] querySourceFeatures(Object[] filter);

  @Keep
  private native void nativeSetTileData(int z, int x, int y, FeatureCollection data);

  @Keep
  private native void nativeInvalidateTile(int z, int x, int y);

  @Keep
  private native void nativeInvalidateBounds(LatLngBounds bounds);

  @Override
  @Keep
  protected native void finalize() throws Throwable;

  private void setTileData(TileID tileId, FeatureCollection data) {
    cancelledTileRequests.remove(tileId);
    nativeSetTileData(tileId.z, tileId.x, tileId.y, data);
  }

  @WorkerThread
  @Keep
  private void fetchTile(int z, int x, int y) {
    AtomicBoolean cancelFlag = new AtomicBoolean(false);
    TileID tileID = new TileID(z, x, y);
    cancelledTileRequests.put(tileID, cancelFlag);
    GeometryTileRequest request =
      new GeometryTileRequest(tileID, provider, queue, cancelledTileRequests, this, cancelFlag);
    executor.execute(request);
  }

  @WorkerThread
  @Keep
  private void cancelTile(int z, int x, int y) {
    AtomicBoolean cancelFlag = cancelledTileRequests.get(new TileID(z, x, y));
    if (cancelFlag != null) {
      cancelFlag.compareAndSet(false, true);
    }
  }

  @Keep
  private void requestProcessed(int z, int x, int y) {
    queue.requestProcessed(new TileID(z, x, y));
  }

  static class TileID {
    public int z;
    public int x;
    public int y;

    TileID(int _z, int _x, int _y) {
      z = _z;
      x = _x;
      y = _y;
    }

    public int hashCode() {
      return Arrays.hashCode(new int[] {z, x, y});
    }

    public boolean equals(Object object) {
      if (object == this) {
        return true;
      }

      if (object == null || getClass() != object.getClass()) {
        return false;
      }

      if (object instanceof TileID) {
        TileID other = (TileID) object;
        return this.z == other.z && this.x == other.x && this.y == other.y;
      }
      return false;
    }
  }

  static class GeometryTileRequest implements Runnable {
    private final TileID id;
    private final GeometryTileProvider provider;
    private final GeometryTileRequestsQueue queue;
    private final Map<TileID, AtomicBoolean> requestMap;
    private final WeakReference<CustomGeometrySource> sourceRef;
    private final AtomicBoolean cancelled;

    GeometryTileRequest(TileID _id, GeometryTileProvider p, GeometryTileRequestsQueue q,
                        Map<TileID, AtomicBoolean> m,
                        CustomGeometrySource _source, AtomicBoolean _cancelled) {
      id = _id;
      provider = p;
      queue = q;
      requestMap = m;
      sourceRef = new WeakReference<>(_source);
      cancelled = _cancelled;
    }

    public void run() {
      if (isCancelled()) {
        onCancelled();
        return;
      }

      FeatureCollection data = provider.getFeaturesForBounds(LatLngBounds.from(id.z, id.x, id.y), id.z);
      CustomGeometrySource source = sourceRef.get();
      if (!isCancelled() && source != null && data != null) {
        source.setTileData(id, data);
      } else {
        onCancelled();
      }
    }

    private void onCancelled() {
      requestMap.remove(id);
      queue.requestProcessed(id);
    }

    Boolean isCancelled() {
      return cancelled.get();
    }

    TileID getId() {
      return id;
    }
  }
}
