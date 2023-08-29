/*
 * Copyright (C) 2013-2018 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.nlp.backend.apple;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import org.microg.nlp.api.LocationHelper;
import org.osmdroid.DefaultResourceProxyImpl;
import org.osmdroid.api.IMapView;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.TileSystem;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.ItemizedOverlay;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class PregrabActivity extends Activity {
    private static final String TAG = PregrabActivity.class.getName();
    private static final boolean DEBUG = false;

    private final List<WifiOverlayItem> items = new ArrayList<>();
    private MapView mapView;
    private WifiLocationDatabase database;
    private WifiCircleOverlay wifisOverlay;
    private CenterOverlay centerOverlay;
    private LocationRetriever retriever;
    private Paint circlePaint;
    private Paint errorPaint;
    private MyLocationNewOverlay myLocationOverlay;
    private static long MAX_AGE = 1000L * 60 * 60 * 24 * 30;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.pregrab);
        database = new WifiLocationDatabase(this);
        retriever = new LocationRetriever();
        mapView = (MapView) findViewById(R.id.map);
        wifisOverlay = new WifiCircleOverlay();
        centerOverlay = new CenterOverlay();
        myLocationOverlay = new MyLocationNewOverlay(this, mapView);
        myLocationOverlay.setDrawAccuracyEnabled(true);
        circlePaint = new Paint();
        circlePaint.setARGB(0, 100, 255, 100);
        circlePaint.setAntiAlias(true);
        errorPaint = new Paint();
        errorPaint.setARGB(0, 255, 100, 100);
        errorPaint.setAntiAlias(true);
        errorPaint.setAlpha(150);
        errorPaint.setStrokeWidth(5);
        errorPaint.setStyle(Paint.Style.STROKE);
        mapView.getOverlays().add(wifisOverlay);
        mapView.getOverlays().add(myLocationOverlay);
        mapView.getOverlays().add(centerOverlay);
        mapView.setMultiTouchControls(true);
        mapView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    centerOverlay.update();
                }
                return false;
            }
        });
        findViewById(R.id.button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findViewById(R.id.button).setEnabled(false);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Location next = database.getNear(LocationHelper.create("temp",
                                mapView.getMapCenter().getLatitude(),
                                mapView.getMapCenter().getLongitude(), 0),
                                1, MAX_AGE).get(0);
                        if (DEBUG) Log.d(TAG, "Based on location: " + next);
                        String now = next.getExtras().getString("MAC_ADDRESS");
                        try {
                            Collection<Location> response = retriever.retrieveLocations(now);
                            WifiLocationDatabase.Editor editor = database.edit();
                            float radius = 0;
                            for (Location location : response) {
                                editor.put(location);
                                if (location.hasAccuracy() && location.getAccuracy() != -1) {
                                    radius = Math.max(location.distanceTo(next), radius);
                                }
                            }
                            editor.end();
                            if (DEBUG) Log.d(TAG, "Downloaded " + response.size() + " APs at " + next
                                    .getLatitude() + "/" + next.getLongitude() + " near " +
                                    mapView.getMapCenter().getLatitude() + "/" + mapView
                                    .getMapCenter().getLongitude());
                            next.setAccuracy(radius);
                            items.add(new WifiOverlayItem(next));
                        } catch (IOException e) {
                            Log.e(TAG, "Exception: " + e);
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                mapView.invalidate();
                                findViewById(R.id.button).setEnabled(true);
                            }
                        });
                    }
                }).start();
            }
        });
        mapView.getController().setZoom(8);
        myLocationOverlay.enableMyLocation();
    }

    private class WifiOverlayItem {
        private GeoPoint point;
        private double latitude;
        private float size;

        private WifiOverlayItem(Location location) {
            size = location.getAccuracy();
            point = new GeoPoint(location.getLatitude(), location.getLongitude());
            latitude = location.getLatitude();
        }
    }

    private class CenterOverlay extends ItemizedOverlay<OverlayItem> {

        public CenterOverlay() {
            super(getResources().getDrawable(android.R.drawable.presence_online),
                    new DefaultResourceProxyImpl(PregrabActivity.this));
        }

        public void update() {
            populate();
        }

        @Override
        protected OverlayItem createItem(int i) {
            return new OverlayItem(null, null, mapView.getMapCenter());
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public boolean onSnapToItem(int x, int y, Point snapPoint, IMapView mapView) {
            return false;
        }
    }

    private class WifiCircleOverlay extends Overlay {

        public WifiCircleOverlay() {
            super(PregrabActivity.this);
        }

        @Override
        protected void draw(Canvas c, MapView osmv, boolean shadow) {
            final int zoomDiff = mapView.getMaxZoomLevel() - mapView.getZoomLevel();
            Point pnt = new Point();
            for (WifiOverlayItem item : items) {
                mapView.getProjection().toPixels(item.point, pnt);
                if (item.size == 0) {
                    c.drawCircle(pnt.x, pnt.y, 20, errorPaint);
                } else {
                    float radius = (float) (item.size / TileSystem.GroundResolution(item.latitude,
                            mapView.getZoomLevel()));
                    circlePaint.setAlpha(50);
                    circlePaint.setStyle(Paint.Style.FILL);
                    c.drawCircle(pnt.x, pnt.y, radius, circlePaint);

                    circlePaint.setAlpha(150);
                    circlePaint.setStyle(Paint.Style.STROKE);
                    c.drawCircle(pnt.x, pnt.y, radius, circlePaint);
                }
            }
        }
    }
}
