/*
 * MojoLoaderUI - Swing front-end for the mojoloader command-line tool
 * (Embedded Micro Mojo v3). Needs Java 17 or later and no extra libraries:
 * it runs the mojoloader binary and shows its output and progress.
 *
 * Run without building:  java MojoLoaderUI.java
 * Build a jar:           make ui   ->  java -jar mojoloader-ui.jar
 */

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class MojoLoaderUI extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final String LOADER_NAME = "mojoloader";
    private static final String DEFAULT_PORT = "/dev/ttyACM0";
    private static final Pattern PROGRESS = Pattern.compile("^(Writing|Verifying):\\s*(\\d+)%$");

    private static final String PREF_PORT = "port";
    private static final String PREF_FILE = "binFile";
    private static final String PREF_LOADER = "loader";
    private static final String PREF_MODE = "mode";

    private final transient Preferences prefs = Preferences.userNodeForPackage(MojoLoaderUI.class);

    private final JComboBox<String> portBox = new JComboBox<>();
    private final JTextField fileField = new JTextField(30);
    private final JTextField loaderField = new JTextField(30);
    private final JRadioButton flashButton = new JRadioButton("Flash");
    private final JRadioButton verifyButton = new JRadioButton("Flash + verify");
    private final JRadioButton ramButton = new JRadioButton("RAM only");
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton fileBrowseButton = new JButton("Browse...");
    private final JButton loaderBrowseButton = new JButton("Browse...");
    private final JButton loadButton = new JButton("Load");
    private final JButton eraseButton = new JButton("Erase flash");
    private final JButton cancelButton = new JButton("Cancel");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JTextArea logArea = new JTextArea(12, 60);

    private transient LoaderWorker worker;

    private MojoLoaderUI() {
        super("Mojo Loader");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                onClose();
            }
        });

        buildUi();
        restoreSettings();
        setBusy(false);

        pack();
        setMinimumSize(new Dimension(520, 380));
        setLocationRelativeTo(null);
    }

    /* ------------------------------------------------------------ */
    /* layout                                                        */
    /* ------------------------------------------------------------ */

    private void buildUi() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));

        portBox.setEditable(true);
        addRow(form, 0, "Serial port:", portBox, refreshButton);
        addRow(form, 1, "Bitstream (.bin):", fileField, fileBrowseButton);
        addRow(form, 2, "Loader program:", loaderField, loaderBrowseButton);

        ButtonGroup modes = new ButtonGroup();
        JPanel modePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        for (JRadioButton b : List.of(flashButton, verifyButton, ramButton)) {
            modes.add(b);
            modePanel.add(b);
        }
        flashButton.setToolTipText("Write the bitstream to flash and load it");
        verifyButton.setToolTipText("Write to flash, read it back and compare, then load it");
        ramButton.setToolTipText("Configure the FPGA only; lost at power-off, flash untouched");
        addRow(form, 3, "Target:", modePanel, null);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 10, 0, 10), logScroll.getBorder()));

        progressBar.setStringPainted(true);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(eraseButton);
        buttons.add(cancelButton);
        buttons.add(loadButton);

        JPanel statusRow = new JPanel(new BorderLayout(10, 0));
        statusRow.add(statusLabel, BorderLayout.CENTER);
        statusRow.add(buttons, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        bottom.add(progressBar, BorderLayout.NORTH);
        bottom.add(statusRow, BorderLayout.SOUTH);

        add(form, BorderLayout.NORTH);
        add(logScroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        refreshButton.addActionListener(e -> refreshPorts(currentPort()));
        fileBrowseButton.addActionListener(e -> browseBitstream());
        loaderBrowseButton.addActionListener(e -> browseLoader());
        loadButton.addActionListener(e -> startLoad());
        eraseButton.addActionListener(e -> startErase());
        cancelButton.addActionListener(e -> cancelRun());
        getRootPane().setDefaultButton(loadButton);
    }

    private static void addRow(JPanel form, int row, String label, JComponent field, JComponent extra) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(3, 0, 3, 8);
        c.anchor = GridBagConstraints.LINE_START;

        c.gridx = 0;
        form.add(new JLabel(label), c);

        c.gridx = 1;
        c.weightx = 1.0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = extra == null ? 2 : 1;
        form.add(field, c);

        if (extra != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.gridwidth = 1;
            c.insets = new Insets(3, 0, 3, 0);
            form.add(extra, c);
        }
    }

    /* ------------------------------------------------------------ */
    /* settings, ports, file choosers                                */
    /* ------------------------------------------------------------ */

    private void restoreSettings() {
        refreshPorts(prefs.get(PREF_PORT, null));
        fileField.setText(prefs.get(PREF_FILE, ""));
        loaderField.setText(prefs.get(PREF_LOADER, findLoader()));
        switch (prefs.get(PREF_MODE, "flash")) {
            case "verify" -> verifyButton.setSelected(true);
            case "ram" -> ramButton.setSelected(true);
            default -> flashButton.setSelected(true);
        }
    }

    private void saveSettings() {
        prefs.put(PREF_PORT, currentPort());
        prefs.put(PREF_FILE, fileField.getText().trim());
        prefs.put(PREF_LOADER, loaderField.getText().trim());
        prefs.put(PREF_MODE, verifyButton.isSelected() ? "verify" : ramButton.isSelected() ? "ram" : "flash");
    }

    private String currentPort() {
        Object item = portBox.getEditor().getItem();
        return item == null ? "" : item.toString().trim();
    }

    /** Lists /dev/ttyACM* and /dev/ttyUSB*, keeping the given port selected if possible. */
    private void refreshPorts(String preferred) {
        List<String> ports = new ArrayList<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(Path.of("/dev"), "tty{ACM,USB}*")) {
            for (Path p : dir) {
                ports.add(p.toString());
            }
        } catch (IOException e) {
            // no /dev (not Linux) or unreadable: the user can still type a port
        }
        ports.sort(null);

        portBox.removeAllItems();
        for (String p : ports) {
            portBox.addItem(p);
        }
        if (preferred != null && !preferred.isEmpty()) {
            portBox.setSelectedItem(preferred);
        } else if (!ports.isEmpty()) {
            portBox.setSelectedIndex(0);
        } else {
            portBox.setSelectedItem(DEFAULT_PORT);
        }
    }

    /** Looks for the mojoloader binary next to this program, then in the current directory. */
    private static String findLoader() {
        List<Path> candidates = new ArrayList<>();
        try {
            Path self = Path.of(MojoLoaderUI.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(self) ? self : self.getParent();
            if (dir != null) {
                candidates.add(dir.resolve(LOADER_NAME));
            }
        } catch (Exception e) {
            // location unknown (e.g. source-file launch): fall through
        }
        candidates.add(Path.of(LOADER_NAME).toAbsolutePath());
        for (Path p : candidates) {
            if (Files.isExecutable(p) && !Files.isDirectory(p)) {
                return p.toString();
            }
        }
        return LOADER_NAME; // rely on PATH
    }

    private void browseBitstream() {
        JFileChooser chooser = new JFileChooser(startDir(fileField.getText()));
        chooser.setFileFilter(new FileNameExtensionFilter("FPGA bitstream (*.bin)", "bin"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            fileField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void browseLoader() {
        JFileChooser chooser = new JFileChooser(startDir(loaderField.getText()));
        chooser.setDialogTitle("Select the mojoloader program");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loaderField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private static File startDir(String current) {
        if (current != null && !current.isBlank()) {
            File parent = new File(current.trim()).getAbsoluteFile().getParentFile();
            if (parent != null && parent.isDirectory()) {
                return parent;
            }
        }
        return new File(System.getProperty("user.home", "."));
    }

    /* ------------------------------------------------------------ */
    /* actions                                                       */
    /* ------------------------------------------------------------ */

    private void startLoad() {
        String file = fileField.getText().trim();
        if (file.isEmpty()) {
            error("Choose a bitstream (.bin) file first.");
            return;
        }
        if (!Files.isRegularFile(Path.of(file))) {
            error("Bitstream file not found:\n" + file);
            return;
        }
        List<String> cmd = baseCommand();
        if (cmd == null) {
            return;
        }
        if (verifyButton.isSelected()) {
            cmd.add("-v");
        } else if (ramButton.isSelected()) {
            cmd.add("-r");
        }
        cmd.add("-f");
        cmd.add(file);
        run(cmd, ramButton.isSelected() ? "Loading to RAM" : "Writing flash");
    }

    private void startErase() {
        int answer = JOptionPane.showConfirmDialog(this,
                "Erase the configuration flash?\nThe FPGA will be blank at the next power-up.",
                "Erase flash", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        List<String> cmd = baseCommand();
        if (cmd == null) {
            return;
        }
        cmd.add("-c");
        run(cmd, "Erasing flash");
    }

    /** Builds "mojoloader -p -d PORT", or returns null after showing what is missing. */
    private List<String> baseCommand() {
        String loader = loaderField.getText().trim();
        String port = currentPort();
        if (loader.isEmpty()) {
            error("Set the path to the mojoloader program.");
            return null;
        }
        if (port.isEmpty()) {
            error("Choose the Mojo's serial port (usually " + DEFAULT_PORT + ").");
            return null;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(loader);
        cmd.add("-p");
        cmd.add("-d");
        cmd.add(port);
        return cmd;
    }

    private void run(List<String> cmd, String what) {
        saveSettings();
        logArea.setText("");
        log("$ " + String.join(" ", cmd));
        progressBar.setValue(0);
        progressBar.setString(null);
        progressBar.setIndeterminate(true); // until the first percentage arrives
        statusLabel.setText(what + "...");
        setBusy(true);
        worker = new LoaderWorker(cmd);
        worker.execute();
    }

    private void cancelRun() {
        if (worker != null) {
            worker.stop();
        }
    }

    private void onClose() {
        if (worker != null) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "A transfer is running. Stop it and quit?\nAn interrupted flash write leaves the flash incomplete.",
                    "Quit", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
            worker.stop();
        }
        saveSettings();
        dispose();
        System.exit(0);
    }

    private void setBusy(boolean busy) {
        for (JComponent c : List.of(portBox, fileField, loaderField, flashButton, verifyButton, ramButton,
                refreshButton, fileBrowseButton, loaderBrowseButton, loadButton, eraseButton)) {
            c.setEnabled(!busy);
        }
        cancelButton.setEnabled(busy);
    }

    private void log(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "Mojo Loader", JOptionPane.ERROR_MESSAGE);
    }

    /* ------------------------------------------------------------ */
    /* background process                                            */
    /* ------------------------------------------------------------ */

    private final class LoaderWorker extends SwingWorker<Integer, String> {

        private final List<String> command;
        private volatile Process process;
        private volatile boolean stopped;

        LoaderWorker(List<String> command) {
            this.command = command;
        }

        void stop() {
            stopped = true;
            Process p = process;
            if (p != null) {
                p.destroy();
            }
        }

        @Override
        protected Integer doInBackground() throws Exception {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            process = p;
            if (stopped) {
                p.destroy();
            }
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    publish(line);
                }
            }
            return p.waitFor();
        }

        @Override
        protected void process(List<String> lines) {
            for (String line : lines) {
                Matcher m = PROGRESS.matcher(line.trim());
                if (m.matches()) {
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(Integer.parseInt(m.group(2)));
                    progressBar.setString(m.group(1) + " " + m.group(2) + "%");
                    statusLabel.setText(m.group(1) + "...");
                } else {
                    log(line);
                }
            }
        }

        @Override
        protected void done() {
            worker = null;
            progressBar.setIndeterminate(false);
            setBusy(false);
            try {
                int exit = get();
                if (stopped) {
                    finish("Cancelled", 0);
                    log("Cancelled. If a flash write was interrupted, write the flash again.");
                } else if (exit == 0) {
                    finish("Done", 100);
                } else {
                    finish("Failed (exit code " + exit + ")", 0);
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                finish("Failed", 0);
                log("Could not run the loader: " + cause.getMessage());
                error("Could not run the loader program:\n" + cause.getMessage()
                        + "\n\nBuild it with \"make\" and check the \"Loader program\" path.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finish("Interrupted", 0);
            }
        }

        private void finish(String status, int percent) {
            statusLabel.setText(status);
            progressBar.setValue(percent);
            progressBar.setString(status);
        }
    }

    /* ------------------------------------------------------------ */

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // keep the default look and feel
            }
            new MojoLoaderUI().setVisible(true);
        });
    }
}
