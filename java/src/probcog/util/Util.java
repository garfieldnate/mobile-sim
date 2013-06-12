package probcog.util;

import java.util.*;

import probcog.sensor.*;

public class Util
{
    /** Give a string of key=token pairs, extract the token
     *  value corresponding to the given key
     **/
    public static String getTokenValue(String params, String tokenKey)
    {
        String[] tokens = params.split(",");
        for (String token: tokens) {
            String[] keyValuePair = token.split("=");
            if (keyValuePair.length < 2)
                continue;
            if (keyValuePair[0].equals(tokenKey))
                return keyValuePair[1];
        }

        return null;    // No token found
    }

    public static ArrayList<String> getPossibleValues(String[] pairs, String key)
    {
        ArrayList<String> values = new ArrayList<String>();
        for (String pair: pairs) {
            String[] keyValuePair = pair.split("=");
            if (keyValuePair[0].equals(key))
                values.add(keyValuePair[1]);
        }

        return values;
    }

    public static String nextValue(ArrayList<String> values, String value)
    {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).equals(value))
                return values.get((i+1)%values.size());
        }
        return null;
    }

    static int id = 0;
    public static int nextID()
    {
        return id++;
    }

    public static boolean equals(double a, double b, double thresh)
    {
        return Math.abs(a-b) < thresh;
    }

    public static double[] toArray(Collection<Double> collection, double[] da)
    {
        if (da == null || da.length < collection.size()) {
            da = new double[collection.size()];
        }
        int i = 0;
        for (Double d: collection) {
            da[i++] = d;
        }

        return da;
    }

    public static int[] toArray(Collection<Integer> collection, int[] ia)
    {
        if (ia == null || ia.length < collection.size()) {
            ia = new int[collection.size()];
        }
        int j = 0;
        for (Integer i: collection) {
            ia[j++] = i;
        }

        return ia;
    }

    public static ArrayList<double[]> extractPoints(Sensor sensor)
    {
        ArrayList<double[]> points = new ArrayList<double[]>();
        int height = sensor.getHeight();
        int width = sensor.getWidth();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] xyzrgb = sensor.getXYZRGB(x,y);
                if (xyzrgb == null)
                    continue;
                //System.out.printf("%f %f %f\n", xyzrgb[0], xyzrgb[1], xyzrgb[2]);

                points.add(xyzrgb);
            }
        }
        return points;
    }



    static public void main(String[] args)
    {
        System.out.printf("%d %d %d\n", nextID(), nextID(), nextID());
    }
}