package probcog.commands.controls;

import java.awt.*;
import javax.swing.*;
import java.io.*;
import java.util.*;

import lcm.lcm.*;

import april.jmat.*;
import april.vis.*;
import april.util.*;

import probcog.util.*;

import magic2.lcmtypes.*;

/** A utility class for generating and debugging potential functions */
public class PotentialUtil
{
    static public enum AttractivePotential
    {
        LINEAR, QUADRATIC, COMBINED
    }

    // XXX Doorway preservation is slow and not great to use, yet.
    static public enum RepulsivePotential
    {
        CLOSEST_POINT, ALL_POINTS, PRESERVE_DOORS
    }

    static public class Params
    {
        public laser_t laser;
        public pose_t pose;
        public double[] goalXYT;

        public Params(laser_t laser, pose_t pose, double[] goalXYT)
        {
            this.laser = laser;
            this.pose = pose;
            this.goalXYT = goalXYT;
        }

        // XXX Other parameters to affect search here. Set to sane default
        // values for normal goto XY operation.

        // Is this value set correctly in config? Reevaluate for new robot.
        public double robotRadius = Util.getConfig().requireDouble("robot.geometry.radius");
        public double fieldSize = 3.0;  // [meters];
        public double fieldRes = 0.05;   // [meters / pixel]

        // attractiveThreshold used for combined method, specifying a distance
        // that, when exceeded, will switch to linear potential from quadratic.
        public AttractivePotential attractivePotential = AttractivePotential.LINEAR;
        public double attractiveWeight = 1.0;
        public double attractiveThreshold = 1.0;

        public RepulsivePotential repulsivePotential = RepulsivePotential.ALL_POINTS;
        public double repulsiveWeight = 5.0;
        public double maxObstacleRange = 2*robotRadius;
    }

    static public ArrayList<double[]> getMinPath(double[] rxy_start,
                                                 PotentialField pf)
    {
        ArrayList<double[]> path = new ArrayList<double[]>();

        double[] currXY = LinAlg.copy(rxy_start);
        int iters = 300;
        while (pf.inRange(currXY[0], currXY[1])) {
            path.add(LinAlg.copy(currXY));
            double[] g = getGradient(currXY, pf);
            currXY[0] += g[0]*pf.getMPP()/4;
            currXY[1] += g[1]*pf.getMPP()/4;

            if (iters-- <= 0)
                break;
        }

        return path;
    }

    /** Get the gradient of a coordinate relative to the robot for the
     *  given potential field. Return as a normalized direction (also relative
     *  to the robot)
     **/
    static public double[] getGradient(double[] rxy,
                                       PotentialField pf)
    {
        double v00 = pf.getRelative(rxy[0], rxy[1]);
        double v10 = pf.getRelative(rxy[0]+pf.getMPP(), rxy[1]);
        double v01 = pf.getRelative(rxy[0], rxy[1]+pf.getMPP());
            double v11 = pf.getRelative(rxy[0]+pf.getMPP(), rxy[1]+pf.getMPP());

        double dx = 0.5*((v10-v00)+(v11-v01));
        double dy = 0.5*((v01-v00)+(v11-v10));

        if (MathUtil.doubleEquals(Math.abs(dx)+Math.abs(dx), 0))
            return new double[2];
        return LinAlg.normalize(new double[] {-dx, -dy});
    }

    /** Given application specific parameters, generate a potential field
     *  locally centered around the robot.
     **/
    static public PotentialField getPotential(Params params)
    {
        double[] robotXYT = LinAlg.matrixToXYT(LinAlg.quatPosToMatrix(params.pose.orientation,
                                                                      params.pose.pos));
        PotentialField pf = new PotentialField(robotXYT,
                                               params.fieldSize,
                                               params.fieldSize,
                                               params.fieldRes);

        addAttractivePotential(params, pf);
        addRepulsivePotential(params, pf);

        return pf;
    }

    /** Add attrative potential to the system in one of three forms.
     *  1) Linear/conical potential. Directly proportional to distance to goal.
     *  2) Quadratic potential. Square of distance to goal.
     *  3) Combined potential. Starts quadratic, but linear beyond some point.
     **/
    static private void addAttractivePotential(Params params,
                                               PotentialField pf)
    {
        double kw = params.attractiveWeight;
        double kt = params.attractiveThreshold;

        for (int y = 0; y < pf.getHeight(); y++) {
            for (int x = 0; x < pf.getWidth(); x++) {
                double[] xy = pf.indexToMeters(x, y);
                double d = LinAlg.distance(xy, params.goalXYT, 2);

                switch (params.attractivePotential) {
                    case LINEAR:
                        pf.addIndex(x, y, d*kw);
                        break;
                    case QUADRATIC:
                        pf.addIndex(x, y, 0.5*d*d*kw);
                        break;
                    case COMBINED:
                        if (d > kt) {
                            pf.addIndex(x, y, kt*kw*(d-0.5*kt));
                        } else {
                            pf.addIndex(x, y, 0.5*d*d*kw);
                        }
                        break;
                    default:
                        System.err.println("ERR: Unknown attractive potential");
                }
            }
        }
    }

    /** Generate repulsive potential based on obstacles observed near the robot.
     *  Derives these range measurements from the provided laser_t in params.
     **/
    static private void addRepulsivePotential(Params params,
                                              PotentialField pf)
    {
        double[] xyt = LinAlg.matrixToXYT(LinAlg.quatPosToMatrix(params.pose.orientation,
                                                                 params.pose.pos));
        double[] invXyt = LinAlg.xytInverse(xyt);

        // Convert laser_t measurements to global coordinates. Ignore ranges
        // that cannot contribute to our potential.
        ArrayList<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < params.laser.nranges; i++) {
            double r = params.laser.ranges[i];

            // Error value
            if (r < 0)
                continue;
            double t = params.laser.rad0 + i*params.laser.radstep;
            double[] xy = new double[] { r*Math.cos(t), r*Math.sin(t) };
            points.add(LinAlg.transform(xyt, xy));
        }

        switch (params.repulsivePotential) {
            case CLOSEST_POINT:
                repulsiveClosestPoint(pf, points, params);
                break;
            case ALL_POINTS:
                repulsiveAllPoints(pf, points, params);
                break;
            case PRESERVE_DOORS:
                repulsivePreserveDoors(pf, points, params);
                break;
            default:
                System.err.println("ERR: Unknown repulsive potential");
        }
    }

    static private void repulsiveClosestPoint(PotentialField pf,
                                              ArrayList<double[]> points,
                                              Params params)
    {
        double kw = params.repulsiveWeight;
        double kr = params.maxObstacleRange;
        double invKr = 1.0/kr;
        double invRad = 1.0/params.robotRadius;

        // Determine the distance to the closest point
        for (int y = 0; y < pf.getHeight(); y++) {
            for (int x = 0; x < pf.getWidth(); x++) {
                double[] xy = pf.indexToMeters(x, y);
                double d = Double.MAX_VALUE;
                for (double[] pxy: points) {
                    d = Math.min(d, LinAlg.distance(xy, pxy, 2));
                }

                // No potential added
                if (d > kr)
                    continue;
                pf.addIndex(x, y, 0.5*kw*LinAlg.sq(1.0/d-invKr));
            }
        }
    }

    static private void repulsiveAllPoints(PotentialField pf,
                                           ArrayList<double[]> points,
                                           Params params)
    {
        double kw = params.repulsiveWeight;
        double kr = params.maxObstacleRange;
        double invKr = 1.0/kr;
        double invRad = 1.0/params.robotRadius;

        for (int y = 0; y < pf.getHeight(); y++) {
            for (int x = 0; x < pf.getWidth(); x++) {
                double[] xy = pf.indexToMeters(x, y);
                double v = 0;
                int cnt = 0;
                for (double[] pxy: points) {
                    double d = LinAlg.distance(xy, pxy, 2);

                    // No potential added
                    if (d > kr)
                        continue;

                    v += 0.5*LinAlg.sq(1.0/d-invKr);
                    cnt++;
                }
                if (cnt > 0) {
                    v /= cnt;
                    pf.addIndex(x, y, kw*v);
                }
            }
        }
    }

    static private void repulsivePreserveDoors(PotentialField pf,
                                               ArrayList<double[]> points,
                                               Params params)
    {
        double kw = params.repulsiveWeight;
        double kr = params.maxObstacleRange;
        double invKr = 1.0/kr;
        double maxRange = 2*params.robotRadius;

        double[] distances = new double[points.size()];
        for (int y = 0; y < pf.getHeight(); y++) {
            for (int x = 0; x < pf.getWidth(); x++) {
                double[] xy = pf.indexToMeters(x, y);
                double v = 0;
                int cnt = 0;

                double[] u = new double[2];
                double min = Double.MAX_VALUE;
                for (int i = 0; i < points.size(); i++) {
                    double[] pxy = points.get(i);
                    double d = LinAlg.distance(xy, pxy, 2);
                    distances[i] = d;

                    // No potential added
                    if (d > maxRange)
                        continue;

                    u[0] += (pxy[0] - xy[0])/d;
                    u[1] += (pxy[1] - xy[1])/d;
                    min = Math.min(min, d);
                }

                boolean aligningForce = false;
                boolean opposingForce = false;
                u = LinAlg.normalize(u);
                for (int i = 0; i < points.size(); i++) {
                    double d = distances[i];

                    if (d > maxRange)
                        continue;

                    double[] pxy = points.get(i);
                    double dp = (u[0]*(pxy[0]-xy[0]) + u[1]*(pxy[1]-xy[1]))/d;
                    if (dp < 0) {
                        opposingForce = true;
                        break;
                    }

                    if (dp > 0.995) {
                        aligningForce = true;
                        break;
                    }
                }

                //if (aligningForce || !opposingForce) {
                //    kr = 2*params.robotRadius;
                //    invKr = 1.0/kr;
                //} else {
                    kr = params.maxObstacleRange;
                    invKr = 1.0/kr;
                //}

                v = 0.5*LinAlg.sq(1.0/min-invKr);
                pf.addIndex(x, y, kw*v);
                //for (int i = 0; i < points.size(); i++) {
                //    double d = distances[i];

                //    // No potential added
                //    if (d > kr)
                //        continue;

                //    v += 0.5*LinAlg.sq(1.0/d-invKr);
                //    cnt++;
                //}
                //if (cnt > 0) {
                //    v /= cnt;
                //    pf.addIndex(x, y, kw*v);
                //}
            }
        }
    }

    static public void main(String[] args)
    {
        double[] goal = new double[] {3.0, 0, 0};
        pose_t pose = new pose_t();
        double[] xyt = new double[] {1.5, 1, 0};
        pose.orientation = LinAlg.rollPitchYawToQuat(new double[] {0, 0, xyt[2]});
        pose.pos = new double[] {xyt[0], xyt[1], 0};

        // Fake a hallway. Wall on right is 1m away, wall on left is 0.5m
        laser_t laser = new laser_t();
        laser.rad0 = (float)(-Math.PI);
        laser.radstep = (float)(Math.toRadians(1));
        laser.nranges = 360;
        laser.ranges = new float[laser.nranges];
        double doorOffset = 0.5;
        double doorSize = 0.9;
        for (int i = 0; i < laser.nranges; i++) {
            double t = laser.rad0 + i*laser.radstep;
            double r = -1;
            if (t < 0) {
                r = (-0.5/Math.sin(t));
                if (r*Math.cos(t) > doorOffset && r*Math.cos(t) < doorOffset+doorSize)
                    r = -1;
            } else if (t > 0) {
                r = (1.0/Math.sin(t));
            }
            if (r > 30 || r < 0)
                r = -1;

            laser.ranges[i] = (float)r;
        }

        LCM.getSingleton().publish("TEST_LASER", laser);

        // Construct the potential field
        Params params = new Params(laser, pose, goal);
        params.attractivePotential = AttractivePotential.COMBINED;
        //params.fieldRes = 0.01;
        //params.repulsivePotential = RepulsivePotential.PRESERVE_DOORS;
        //params.maxObstacleRange = 0.1;

        // Wait for keypress
        //try {
        //    System.out.println("Press ENTER to continue:");
        //    System.in.read();
        //} catch (IOException ioex) {}

        Tic tic = new Tic();
        PotentialField pf = PotentialUtil.getPotential(params);
        System.out.printf("Computation completed in %f [s]\n", tic.toc());

        // Evaluate gradients at fixed locations around the robot
        ArrayList<double[]> rxys = new ArrayList<double[]>();
        for (double y = -1.5; y <= 1.5; y+= 2*params.fieldRes) {
            for (double x = -1.5; x <= 1.5; x+= 2*params.fieldRes) {
                rxys.add(new double[] {x, y});
            }
        }
        ArrayList<double[]> gradients = new ArrayList<double[]>();
        for (double[] rxy: rxys)
            gradients.add(getGradient(rxy, pf));


        JFrame jf = new JFrame("Potential test");
        jf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        jf.setLayout(new BorderLayout());
        jf.setSize(800, 600);

        VisWorld vw = new VisWorld();
        VisLayer vl = new VisLayer(vw);
        VisCanvas vc = new VisCanvas(vl);
        jf.add(vc, BorderLayout.CENTER);

        // Render the field
        int[] map = new int[] {0xffffff00,
                               0xffff00ff,
                               0x0007ffff,
                               0xff0000ff,
                               0xff2222ff};
        double minVal = pf.getMinValue();
        double maxVal = Math.max(5*minVal, params.repulsiveWeight);
        ColorMapper cm = new ColorMapper(map, minVal, maxVal);

        double[][] M = LinAlg.quatPosToMatrix(pose.orientation,
                                              pose.pos);
        VisWorld.Buffer vb = vw.getBuffer("potential-field");
        vb.setDrawOrder(-10);
        vb.addBack(new VisChain(M, pf.getVisObject(cm)));
        vb.swap();

        // Render a grid
        vb = vw.getBuffer("grid");
        vb.addBack(new VzGrid());
        vb.swap();

        // Render a robot
        vb = vw.getBuffer("robot");
        vb.setDrawOrder(10);
        vb.addBack(new VisChain(M, new VzRobot(new VzMesh.Style(Color.green))));
        vb.swap();

        // Render some local potentials
        vb = vw.getBuffer("gradients");
        vb.setDrawOrder(20);
        ArrayList<double[]> bpoints = new ArrayList<double[]>();
        ArrayList<double[]> gpoints = new ArrayList<double[]>();
        for (int i = 0; i < rxys.size(); i++) {
            double[] rxy = rxys.get(i);
            double[] u = gradients.get(i);

            double[] p0 = LinAlg.transform(M, rxy);
            double[] p1 = LinAlg.transform(M, LinAlg.add(LinAlg.scale(u, 1*params.fieldRes), rxy));
            bpoints.add(p0);
            gpoints.add(p0);
            gpoints.add(p1);
        }
        vb.addBack(new VzPoints(new VisVertexData(bpoints),
                                new VzPoints.Style(Color.black, 2)));
        vb.addBack(new VzLines(new VisVertexData(gpoints),
                               VzLines.LINES,
                               new VzLines.Style(Color.gray, 1)));
        vb.swap();

        vb = vw.getBuffer("laser");
        vb.setDrawOrder(100);
        ArrayList<double[]> lpoints = new ArrayList<double[]>();
        for (int i = 0; i < laser.nranges; i++) {
            double r = laser.ranges[i];
            if (r < 0)
                continue;

            double t = laser.rad0 + i*laser.radstep;
            lpoints.add(new double[] {r*Math.cos(t), r*Math.sin(t)});
        }
        vb.addBack(new VisChain(M,
                                new VzPoints(new VisVertexData(lpoints),
                                             new VzPoints.Style(Color.orange, 3))));
        vb.swap();

        vb = vw.getBuffer("path");
        vb.setDrawOrder(50);
        ArrayList<double[]> path = getMinPath(new double[2], pf);
        vb.addBack(new VisChain(M,
                                new VzLines(new VisVertexData(path),
                                           VzLines.LINE_STRIP,
                                           new VzLines.Style(Color.green, 2))));

        vb.swap();

        jf.setVisible(true);
    }
}