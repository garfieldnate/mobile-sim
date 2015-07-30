package probcog.rosie;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JSplitPane;

import probcog.rosie.actuation.MobileActuationConnector;
import probcog.rosie.perception.MobilePerceptionConnector;
import edu.umich.rosie.AgentMenu;
import edu.umich.rosie.language.ChatPanel;
import edu.umich.rosie.language.InternalMessagePasser;
import edu.umich.rosie.language.InstructorMessagePanel;
import edu.umich.rosie.language.LanguageConnector;
import edu.umich.rosie.soar.SoarAgent;
import april.util.GetOpt;
import april.util.StringUtil;

public class RosieGUI extends JFrame
{
	private SoarAgent soarAgent;

	private JButton startStopButton;
	private JButton stopRobotButton;

	private MobilePerceptionConnector perception;
	private MobileActuationConnector actuation;
	private LanguageConnector language;

    public RosieGUI(Properties props)
    {
		super("Rosie Chat");

    	//this.setSize(800, 650);
    	this.setSize(1000, 450);
    	getContentPane().setLayout(new BoxLayout(this.getContentPane(), BoxLayout.LINE_AXIS));
        addWindowListener(new WindowAdapter() {
        	public void windowClosing(WindowEvent w) {
        		soarAgent.kill();
        	}
     	});
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

    	soarAgent = new SoarAgent(props);

    	actuation = new MobileActuationConnector(soarAgent, props);
    	soarAgent.setActuationConnector(actuation);

    	perception = new MobilePerceptionConnector(soarAgent, props);
    	soarAgent.setPerceptionConnector(perception);
    	
    	InternalMessagePasser messagePasser = new InternalMessagePasser();
    	
    	language = new LanguageConnector(soarAgent, props, messagePasser);
    	soarAgent.setLanguageConnector(language);

    	ChatPanel chat = new ChatPanel(soarAgent, this, messagePasser);

    	setupMenu();

    	soarAgent.createAgent();

    	add(chat);

    	String[] messages = new String[]{
    			"Deliver the soda to the kitchen",
    			"The goal is that the soda is in the kitchen",
    			"Pick up the soda",
    			"Ok",
    			"Go to the kitchen",
    			"Follow the right wall until you see a door",
    			"You are in the kitchen",
    			"Put down the soda"
    	};
    	add(new InstructorMessagePanel(chat, messages));

//    	CommandPanel commandPanel = new CommandPanel(soarAgent);
//
//    	JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chat, commandPanel);
//    	splitPane.setDividerLocation(400);
//    	this.add(splitPane);

    	this.setVisible(true);
    }

    private void setupMenu(){
    	JMenuBar menuBar = new JMenuBar();

    	startStopButton = new JButton("START");
        startStopButton.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent arg0) {
				if(soarAgent.isRunning()){
					startStopButton.setText("START");
					soarAgent.stop();
				} else {
					startStopButton.setText("STOP");
					soarAgent.start();
				}
			}
        });
        menuBar.add(startStopButton);

    	menuBar.add(new AgentMenu(soarAgent));

    	language.createMenu(menuBar);
    	perception.createMenu(menuBar);
    	actuation.createMenu(menuBar);


    	stopRobotButton = new JButton("STOP");
    	stopRobotButton.setBackground(Color.red);
        stopRobotButton.addActionListener(new ActionListener(){
			public void actionPerformed(ActionEvent arg0) {
				actuation.processStopCommand(null);
			}
        });
        menuBar.add(stopRobotButton);

    	this.setJMenuBar(menuBar);
    }


    public static void main(String[] args) {

        GetOpt opts = new GetOpt();

        opts.addBoolean('h', "help", false, "Show this help screen");
        opts.addBoolean('d', "debug", true, "Show the soar debugger");
        opts.addString('c', "config", null, "The config file for Rosie");

        if (!opts.parse(args)) {
            System.err.println("ERR: Error parsing args - "+opts.getReason());
            System.exit(1);
        }
        if (opts.getBoolean("help")) {
            opts.doHelp();
            System.exit(0);
        }

        String configFile = opts.getString("config");
        if(configFile == null){
        	configFile = StringUtil.replaceEnvironmentVariables("$ROSIE_CONFIG");
        }

        if(configFile.equals("")){
          System.err.println("ERR: No $ROSIE_CONFIG environment variable set");
          System.exit(1);
        }

        // Load the properties file
        Properties props = new Properties();
        try {
			props.load(new FileReader(configFile));
		} catch (IOException e) {
			System.out.println("File not found: " + configFile);
			e.printStackTrace();
			return;
		}

        new RosieGUI(props);
    }
}
