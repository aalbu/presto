/*
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
package io.trino.geospatial;

import com.esri.core.geometry.Envelope;
import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.GeometryCursor;
import com.esri.core.geometry.GeometryEngine;
import com.esri.core.geometry.MultiVertexGeometry;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.Polygon;
import com.esri.core.geometry.ogc.OGCGeometry;
import com.esri.core.geometry.ogc.OGCPoint;
import com.esri.core.geometry.ogc.OGCPolygon;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import io.trino.spi.TrinoException;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;

public final class GeometryUtils
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final String TYPE_ATTRIBUTE = "type";
    private static final String COORDINATES_ATTRIBUTE = "coordinates";
    private static final Map<String, String> EMPTY_ATOMIC_GEOMETRY_JSON_OVERRIDE = ImmutableMap.of(
            "LineString", "{\"type\":\"LineString\",\"coordinates\":[]}",
            "Point", "{\"type\":\"Point\",\"coordinates\":[]}");
    private static final Map<String, org.locationtech.jts.geom.Geometry> EMPTY_ATOMIC_GEOMETRY_OVERRIDE = ImmutableMap.of(
            "Polygon", GEOMETRY_FACTORY.createPolygon(),
            "Point", GEOMETRY_FACTORY.createPoint());

    private GeometryUtils() {}

    /**
     * Copy of com.esri.core.geometry.Interop.translateFromAVNaN
     * <p>
     * deserializeEnvelope needs to recognize custom NAN values generated by
     * ESRI's serialization of empty geometries.
     */
    private static double translateFromAVNaN(double n)
    {
        return n < -1.0E38D ? (0.0D / 0.0) : n;
    }

    /**
     * Copy of com.esri.core.geometry.Interop.translateToAVNaN
     * <p>
     * JtsGeometrySerde#serialize must serialize NaN's the same way ESRI library does to achieve binary compatibility
     */
    public static double translateToAVNaN(double n)
    {
        return (Double.isNaN(n)) ? -Double.MAX_VALUE : n;
    }

    public static boolean isEsriNaN(double d)
    {
        return Double.isNaN(d) || Double.isNaN(translateFromAVNaN(d));
    }

    public static int getPointCount(OGCGeometry ogcGeometry)
    {
        GeometryCursor cursor = ogcGeometry.getEsriGeometryCursor();
        int points = 0;
        while (true) {
            com.esri.core.geometry.Geometry geometry = cursor.next();
            if (geometry == null) {
                return points;
            }

            if (geometry.isEmpty()) {
                continue;
            }

            if (geometry instanceof Point) {
                points++;
            }
            else {
                points += ((MultiVertexGeometry) geometry).getPointCount();
            }
        }
    }

    public static Envelope getEnvelope(OGCGeometry ogcGeometry)
    {
        GeometryCursor cursor = ogcGeometry.getEsriGeometryCursor();
        Envelope overallEnvelope = new Envelope();
        while (true) {
            Geometry geometry = cursor.next();
            if (geometry == null) {
                return overallEnvelope;
            }

            Envelope envelope = new Envelope();
            geometry.queryEnvelope(envelope);
            overallEnvelope.merge(envelope);
        }
    }

    public static boolean disjoint(Envelope envelope, OGCGeometry ogcGeometry)
    {
        GeometryCursor cursor = ogcGeometry.getEsriGeometryCursor();
        while (true) {
            Geometry geometry = cursor.next();
            if (geometry == null) {
                return true;
            }

            if (!GeometryEngine.disjoint(geometry, envelope, null)) {
                return false;
            }
        }
    }

    public static boolean contains(OGCGeometry ogcGeometry, Envelope envelope)
    {
        GeometryCursor cursor = ogcGeometry.getEsriGeometryCursor();
        while (true) {
            Geometry geometry = cursor.next();
            if (geometry == null) {
                return false;
            }

            if (GeometryEngine.contains(geometry, envelope, null)) {
                return true;
            }
        }
    }

    public static boolean isPointOrRectangle(OGCGeometry ogcGeometry, Envelope envelope)
    {
        if (ogcGeometry instanceof OGCPoint) {
            return true;
        }

        if (!(ogcGeometry instanceof OGCPolygon)) {
            return false;
        }

        Polygon polygon = (Polygon) ogcGeometry.getEsriGeometry();
        if (polygon.getPathCount() > 1) {
            return false;
        }

        if (polygon.getPointCount() != 4) {
            return false;
        }

        Set<Point> corners = new HashSet<>();
        corners.add(new Point(envelope.getXMin(), envelope.getYMin()));
        corners.add(new Point(envelope.getXMin(), envelope.getYMax()));
        corners.add(new Point(envelope.getXMax(), envelope.getYMin()));
        corners.add(new Point(envelope.getXMax(), envelope.getYMax()));

        for (int i = 0; i < 4; i++) {
            Point point = polygon.getPoint(i);
            if (!corners.contains(point)) {
                return false;
            }
        }

        return true;
    }

    public static org.locationtech.jts.geom.Geometry jtsGeometryFromJson(String json)
    {
        try {
            org.locationtech.jts.geom.Geometry emptyGeoJsonOverride = getEmptyGeometryOverride(json);
            if (emptyGeoJsonOverride != null) {
                return emptyGeoJsonOverride;
            }
            return new GeoJsonReader().read(json);
        }
        catch (ParseException | IllegalArgumentException e) {
            throw new TrinoException(INVALID_FUNCTION_ARGUMENT, "Invalid GeoJSON: " + e.getMessage(), e);
        }
    }

    /**
     * Return an empty geometry in the cases in which the locationtech library
     * doesn't when the coordinates attribute is an empty array. In particular,
     * these two cases are handled by the underlying library as follows:
     * {type:Point, coordinates:[]} -> POINT (0 0)
     * {type:Polygon, coordinates[]} -> Exception during parsing
     * To circumvent these inconsistencies, we catch this upfront and return
     * the correct empty geometry.
     * TODO: Remove if/when https://github.com/locationtech/jts/issues/684 is fixed.
     */
    private static org.locationtech.jts.geom.Geometry getEmptyGeometryOverride(String json)
    {
        try {
            JsonNode jsonNode = OBJECT_MAPPER.readTree(json);
            JsonNode typeNode = jsonNode.get(TYPE_ATTRIBUTE);
            if (typeNode != null) {
                org.locationtech.jts.geom.Geometry emptyGeometry = EMPTY_ATOMIC_GEOMETRY_OVERRIDE.get(typeNode.textValue());
                if (emptyGeometry != null) {
                    JsonNode coordinatesNode = jsonNode.get(COORDINATES_ATTRIBUTE);
                    if (coordinatesNode != null && coordinatesNode.isArray() && coordinatesNode.isEmpty()) {
                        return emptyGeometry;
                    }
                }
            }
        }
        catch (JsonProcessingException e) {
            // Ignore and have subsequent GeoJsonReader throw
        }
        return null;
    }

    public static String jsonFromJtsGeometry(org.locationtech.jts.geom.Geometry geometry)
    {
        String geoJsonOverride = getEmptyGeoJsonOverride(geometry);
        if (geoJsonOverride != null) {
            return geoJsonOverride;
        }

        return new GeoJsonWriter().write(geometry);
    }

    /**
     * Return GeoJSON with an empty coordinate array when the geometry is empty. This
     * overrides the behavior of the locationtech library which returns invalid
     * GeoJSON in these cases. For example, in the case of an empty point,
     * locationtech would return:
     * {type:Point, coordinates:, ...}
     * TODO: Remove if/when https://github.com/locationtech/jts/issues/411 is fixed
     */
    private static String getEmptyGeoJsonOverride(org.locationtech.jts.geom.Geometry geometry)
    {
        if (geometry.isEmpty()) {
            return EMPTY_ATOMIC_GEOMETRY_JSON_OVERRIDE.get(geometry.getGeometryType());
        }
        return null;
    }
}
