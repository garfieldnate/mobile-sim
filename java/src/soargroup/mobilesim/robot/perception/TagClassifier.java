package soargroup.mobilesim.robot.perception;

import java.io.*;
import java.util.*;

import april.config.*;
import april.jmat.*;
import april.util.*;
import april.tag.TagDetection;

import soargroup.mobilesim.util.*;

// LCM Types
import lcm.lcm.*;
import soargroup.mobilesim.lcmtypes.classification_t;
import soargroup.mobilesim.lcmtypes.classification_list_t;
import soargroup.mobilesim.lcmtypes.tag_classification_t;
import soargroup.mobilesim.lcmtypes.tag_classification_list_t;
import soargroup.mobilesim.lcmtypes.tag_detection_t;
import soargroup.mobilesim.lcmtypes.tag_detection_list_t;

public class TagClassifier
{
    String tagConfig = Util.getConfig().requireString("tag_config");
    double tagSize_m = Util.getConfig().requireDouble("tag_detection.tag.size_m");

    //static Random classifierRandom = new Random(104395301);
    Random classifierRandom = new Random();
    LCM lcm = LCM.getSingleton();
    TagHistory history = new TagHistory();

    HashMap<Integer, ArrayList<TagClass>> idToTag;
    HashMap<String, Set<Integer> > tagClassToIDs;
    HashSet<String> tagClasses = new HashSet<String>();

    public TagClassifier() throws IOException
    {
        this(true);
    }

    /**
     * Read the config file and store information about each tag. Each
     * tag can have multiple "TagClasses" associated, and each tag class
     * contains a label name and a mean and standard deviation that
     * define the probability it belongs to the labeled class. Multiple
     * tags can belong to the same class. If an april tag has mulitple
     * tag classes associated with it they will all be reported when we
     * see it and publish classifications.
     **/
    public TagClassifier(boolean useLcm) throws IOException
    {
        Config config = new ConfigFile(tagConfig);

        idToTag = new HashMap<Integer, ArrayList<TagClass>>();
        tagClassToIDs = new HashMap<String, Set<Integer> >();

        // Read in all possible tag classes with associated tag ids
        // and store them in hashmap accessed by tag id.
        for (int i = 0;; i++) {

            String[] labels = config.getStrings("classes.c"+i+".labels", null);
            double[] probs = config.getDoubles("classes.c"+i+".probs", null);
            int[] ids  = config.getInts("classes.c"+i+".ids", null);
            double mean = config.getDouble("classes.c"+i+".mean", 0);
            double stddev = config.getDouble("classes.c"+i+".stddev", 0);
            double minRange = config.getDouble("classes.c"+i+".minRange", 0);
            double maxRange = config.getDouble("classes.c"+i+".maxRange", 0);

            if (labels == null)
                break;
            
            // The first label detected is assumed to be the ground truth
            // labeling of this object. This is used to map ground truth
            // classes to IDs in tagClassToIDs. If this is the first such
            // labeling we've seen, create the ID first.
            if (!tagClassToIDs.containsKey(labels[0]))
                tagClassToIDs.put(labels[0], new HashSet<Integer>());

            // Get all possible classes we *might* see, ignoring the empty
            // string NULL label.
            for (String label: labels) {
                if (label.equals(""))
                    continue;
                tagClasses.add(label);
            }

            ArrayList<String> labelList = new ArrayList<String>();
            for (String label: labels)
                labelList.add(label);
            ArrayList<Double> probList = new ArrayList<Double>();
            for (double prob: probs)
                probList.add(prob);

            // Populate a tag class, which is used to sample detections in the
            // on future tag observations. idToTag contains mappings of IDs to
            // more than one possible tag class, meaning that some ID can map
            // to multiple classes (e.g. color and shape in blocks world). Also
            // remember to add all the IDs to the ground truth class-to-IDs
            // structure.
            TagClass tag = new TagClass(labelList, probList, mean, stddev, minRange, maxRange);
            for(Integer id : ids) {
                ArrayList<TagClass> allTags;
                if(idToTag.containsKey(id))
                    allTags = idToTag.get(id);
                else
                    allTags = new ArrayList<TagClass>();

                allTags.add(tag);
                idToTag.put(id, allTags);
                tagClassToIDs.get(labels[0]).add(id);
            }
        }

        if (useLcm)
            new ListenerThread().start();
    }

    /** Get an exhaustive list of all of the tag IDs matching a particular class.
     *  Only reports IDs matching ground-truth labelings. */
    public Set<Integer> getIDsForClass(String tagClass)
    {
        if (tagClassToIDs.containsKey(tagClass))
            return tagClassToIDs.get(tagClass);
        return new HashSet<Integer>();
    }

    /** Get an exhaustive list of the classes of tags that exist in the world. */
    public Set<String> getAllClasses()
    {
        return tagClasses;  // Not data safe. READ ONLY USE PLEASE
    }

    /** Get the classes associated with a particular tag. Really, we mean
     *  labels here. LANGUAGE CONSISTENCY, PLEASE. */
    public Set<String> getClasses(int id)
    {
        HashSet<String> classes = new HashSet<String>();
        ArrayList<TagClass> tcs = idToTag.get(id);
        if (tcs == null)
            return classes;

        for (TagClass tc: tcs)
            classes.addAll(tc.labels);

        return classes;
    }

    // XXX This will break for multi-label cases.
    /** Expose the probability that a tag is CORRECTLY labeled. Assumes you
     *  only care about the first class (since we only ever have one class
     *  right now.
     **/
    public double correctProbability(int tagID)
    {
        if (!idToTag.containsKey(tagID))
            return 0;

        ArrayList<TagClass> tcs = idToTag.get(tagID);
        if (tcs.size() < 1)
            return 0;

        TagClass tc = tcs.get(0);
        return correctProbability(tc);
    }

    private double correctProbability(TagClass tc)
    {
        return tc.probs.get(0);
    }

    /** Expose the ground-truth labeling of a particular tag, again, assuming
     *  you don't have multiple classes. Just grabs the first one it can find.
     **/
    public String correctClass(int tagID)
    {
        if (!idToTag.containsKey(tagID))
            return  "";

        ArrayList<TagClass> tcs = idToTag.get(tagID);
        if (tcs.size() < 1)
            return "";

        TagClass tc = tcs.get(0);
        return correctClass(tc);
    }

    private String correctClass(TagClass tc)
    {
        return tc.labels.get(0);
    }

    /** Sample a detection range for a given tag ID. Only works properly for
     *  single-label-per-object setups.
     **/
    public double sampleRange(int tagID)
    {
        if (!idToTag.containsKey(tagID))
            return -1;

        ArrayList<TagClass> tcs = idToTag.get(tagID);
        if (tcs.size() < 1)
            return -1;

        TagClass tc = tcs.get(0);
        return sampleRange(tc);
    }

    private double sampleRange(TagClass tc)
    {
        double v = MathUtil.clamp(classifierRandom.nextGaussian(), -3, 3);
        double diff = tc.maxRange - tc.minRange;
        double range = (tc.minRange+diff/2) + v*(diff/6);
        return range;
    }

    /** Get the max range we are capable of sensing this tag. Single-label only*/
    public double getMaxRange(int tagID)
    {
        if (!idToTag.containsKey(tagID))
            return -1;

        ArrayList<TagClass> tcs = idToTag.get(tagID);
        if (tcs.size() < 1)
            return -1;

        TagClass tc = tcs.get(0);
        return tc.maxRange;
    }

    public ArrayList<tag_classification_t> classifyTag(int id, double[] xyzrpy)
    {
        return classifyTag(id, xyzrpy, false);
    }

    /** Return a list of classifications for a tag of a given ID and xyzrpy
     *  relative to the robot. This actually supports multiple detections,
     *  to some extent.
     **/
    public ArrayList<tag_classification_t> classifyTag(int id, double[] xyzrpy, boolean perfect)
    {
        ArrayList<tag_classification_t> classies = new ArrayList<tag_classification_t>();
        ArrayList<TagClass> tcs = idToTag.get(id);
        if (tcs == null)
            return classies;    // No config entries

        for (TagClass tc: tcs) {
            double v = classifierRandom.nextDouble();
            if (perfect)
                v = 0.0;

            double sum = 0.0;
            String label = correctClass(tc);
            for (int i = 0; i < tc.labels.size(); i++) {
                sum += tc.probs.get(i);
                if (sum >= v) {
                    label = tc.labels.get(i);
                    break;
                }
            }

            tag_classification_t classy = new tag_classification_t();
            classy.utime = TimeUtil.utime();
            classy.name = label;
            classy.range = sampleRange(tc); // When perfect, what should this do?
            classy.tag_id = id;
            classy.xyzrpy = xyzrpy;
            classy.confidence = sampleConfidence(tc.mean, tc.stddev);
            classies.add(classy);
        }

        return classies;
    }

    private void publishDetections(tag_detection_list_t tagList)
    {
        ArrayList<tag_classification_t> classies = new ArrayList<tag_classification_t>();

        for (int n=0; n < tagList.ndetections; n++) {
            tag_detection_t td = tagList.detections[n];

            // Ignore tags we can't map to anything
            if (!idToTag.containsKey(td.id))
                continue;

            // Eventually, we may want to do calibration correction here.
            // For now, let's just trust it .
            TagDetection tag = new TagDetection();
            tag.id = td.id;
            tag.hammingDistance = td.hamming_dist;
            tag.homography = new double[3][];
            for (int i = 0; i < 3; i++) {
                tag.homography[i] = new double[3];
                for (int j = 0; j < 3; j++) {
                    tag.homography[i][j] = (double)td.H[i][j];
                }
            }
            tag.cxy = new double[] {td.cxy[0], td.cxy[1]};
            tag.p = new double[][] {{td.pxy[0][0], td.pxy[0][1]},
                                    {td.pxy[1][0], td.pxy[1][1]},
                                    {td.pxy[2][0], td.pxy[2][1]},
                                    {td.pxy[3][0], td.pxy[3][1]}};

            double[][] T2B = TagUtil.getTagToPose(tag, tagSize_m);
            double[] xyzrpy = LinAlg.matrixToXyzrpy(T2B);


            ArrayList<tag_classification_t> cs = classifyTag(tag.id, xyzrpy);

            // Incorporate tag history stuff
            history.addObservations(cs, tagList.utime);
            cs = history.getLabels(tag.id, xyzrpy, tagList.utime);
            classies.addAll(cs);
            //if (history.isVisible(tag.id, dist, tagList.utime)) {
            //    classy.name = history.getLabel(tag.id, tagList.utime);
            //    classy.range = dist;
            //    classies.add(classy);
            //}
        }

        classification_list_t classy_list = new classification_list_t();
        classy_list.utime = TimeUtil.utime();
        classy_list.num_classifications = classies.size();
        classy_list.classifications = classies.toArray(new classification_t[classies.size()]);

        lcm.publish("CLASSIFICATIONS", classy_list);
    }


    class ListenerThread extends Thread implements LCMSubscriber
    {
        public ListenerThread()
        {
            lcm.subscribe("TAG_DETECTIONS", this);
        }

        public void run()
        {
            while (true) {
                TimeUtil.sleep(1000/60);
            }
        }


        public void messageReceived(LCM lcm, String channel, LCMDataInputStream ins)
        {
            try {
                messageReceivedEx(lcm, channel, ins);
            } catch (IOException ex) {
                System.out.println("WRN: "+ex);
            }
        }


        private void messageReceivedEx(LCM lcm, String channel, LCMDataInputStream ins) throws IOException
        {
            if (channel.equals("TAG_DETECTIONS")) {
                tag_detection_list_t tagList = new tag_detection_list_t(ins);
                publishDetections(tagList);
            }
        }
    }


    /**
     * Given the mean and standard deviation, get a Gaussian probability.
     **/
    private double sampleConfidence(double u, double s)
    {
        return MathUtil.clamp(u + classifierRandom.nextGaussian()*s, 0, 1);
    }

    /**
     * Keep track of the information associated with each tag id. Tags
     * belong to classes (i.e. door, hallway, red) and have some
     * associated probability (this mimics our uncertainly of real-world
     * classification.
     **/
    class TagClass
    {
        ArrayList<String> labels;
        ArrayList<Double> probs;
        double mean;
        double stddev;
        double minRange;
        double maxRange;

        public TagClass(ArrayList<String> labels,
                        ArrayList<Double> probs,
                        double mean,
                        double stddev,
                        double minRange,
                        double maxRange)
        {
            // Assume that the FIRST label is the real one.
            this.labels = labels;
            this.probs = probs;
            this.mean = mean;
            this.stddev = stddev;
            this.minRange = minRange;
            this.maxRange = maxRange;
        }

        public String toString()
        {
            return String.format("%f %f", minRange, maxRange);
        }
    }

    public static void main(String[] args)
    {
        try {
           TagClassifier tc = new TagClassifier(true);
         } catch (IOException ioex) {
            System.err.println("ERR: Error starting tag classifier");
            ioex.printStackTrace();
            System.exit(1);
        }
    }
}
