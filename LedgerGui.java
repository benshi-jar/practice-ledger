import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Provides a Swing interface backed by an existing {@link PracticeLedger}.
 * <p>Create and interact with this class on Swing's event dispatch thread.
 * Session changes are applied to the ledger before refreshing the display.
 * File operations use background workers and independent ledger snapshots.</p>
 * <p>The window tracks unsaved ledger changes separately from unapplied form
 * edits. Startup does not automatically load a file. Its initial save path is
 * {@code practice-ledger.json} in the working directory.</p>
 */

public class LedgerGui {
    private final PracticeLedger ledger;
    private final JFrame frame = new JFrame("Practice Ledger");
    private final DefaultTableModel tableModel =
            new DefaultTableModel(new String[]{"ID", "Topic", "Minutes"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
                @Override public Class<?> getColumnClass(int column) {
                    return column == 1 ? String.class : Integer.class;
                }
            };
    private final JTable table = new JTable(tableModel);
    private final JTextField topicField = new JTextField(18);
    private final JTextField minutesField = new JTextField(6);
    private final JTextField searchField = new JTextField(18);
    private final JLabel totalLabel = new JLabel();
    private final JLabel fileLabel = new JLabel();
    private final JLabel statusLabel = new JLabel("Ready. Load an existing file or add a session.");
    private Path currentFile = Path.of("practice-ledger.json").toAbsolutePath();
    private String activeTopic;
    private boolean dirty;
    private boolean formDirty;
    private boolean updatingForm;
    private boolean busy;
    private boolean knownFile;

    /**
     * Builds the window, controls, and event listeners without displaying the frame.
     * @param ledger non-null ledger to display and modify; accessed on the event dispatch thread
     */

    public LedgerGui(PracticeLedger ledger) {
        this.ledger = ledger;
        dirty = !ledger.listSessions().isEmpty() || ledger.getNextId() != 1;
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(900, 570);
        frame.setMinimumSize(new Dimension(820, 500));
        frame.setLocationRelativeTo(null);
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        frame.setContentPane(content);

        JPanel top = new JPanel(new GridLayout(3, 1, 0, 4));
        JPanel files = row();
        files.add(button("Save", () -> saveCurrent(null)));
        files.add(button("Save as...", this::saveAs));
        files.add(button("Load...", this::chooseLoad));
        files.add(button("Longest session", this::showLongest));
        files.add(button("Exit", this::requestClose));
        top.add(files);

        JPanel form = row();
        form.add(new JLabel("Topic:")); form.add(topicField);
        form.add(new JLabel("Minutes:")); form.add(minutesField);
        form.add(button("Add session", () -> submitForm(false)));
        form.add(button("Update selected", () -> submitForm(true)));
        top.add(form);

        JPanel search = row();
        search.add(new JLabel("Exact topic:")); search.add(searchField);
        search.add(button("Filter", this::applyFilter));
        search.add(button("Show all", () -> {
            activeTopic = null; searchField.setText(""); refreshView();
        }));
        search.add(button("Delete topic...", this::deleteTopic));
        top.add(search);
        content.add(top, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        content.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        JPanel actions = row();
        actions.add(button("Delete selected...", this::deleteSelected));
        actions.add(button("Clear form / selection", () -> {
            if (discardFormEdits()) clearForm();
        }));
        bottom.add(actions, BorderLayout.NORTH);
        JPanel information = new JPanel(new GridLayout(3, 1, 0, 4));
        information.add(totalLabel); information.add(fileLabel); information.add(statusLabel);
        bottom.add(information, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);

        DocumentListener edits = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { changed(); }
            public void removeUpdate(DocumentEvent e) { changed(); }
            public void changedUpdate(DocumentEvent e) { changed(); }
            private void changed() {
                if (!updatingForm) { formDirty = true; updateTitle(); }
            }
        };
        topicField.getDocument().addDocumentListener(edits);
        minutesField.getDocument().addDocumentListener(edits);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updatingForm) fillFromSelection();
        });
        searchField.addActionListener(event -> applyFilter());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { requestClose(); }
        });
        refreshView();
    }

    /**
     * Creates a left-aligned row for related controls.
     * @return an empty panel with the shared spacing settings
     */

    private JPanel row() { return new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3)); }

    /**
     * Creates a button whose action is ignored while a file operation is busy.
     * @param text button label
     * @param action action to run on the event dispatch thread
     * @return the configured button
     */

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(event -> { if (!busy) action.run(); });
        return button;
    }

    /**
     * Rebuilds table rows from the ledger and updates whole-ledger and visible totals.
     * <p>The active topic filter is applied without changing ledger order. Table
     * selection is reset, while programmatic selection callbacks are suppressed.</p>
     */

    private void refreshView() {
        updatingForm = true;
        try {
            tableModel.setRowCount(0);
            List<Session> visible = activeTopic == null
                    ? ledger.listSessions() : ledger.findSessionsByTopic(activeTopic);
            long shownMinutes = 0;
            for (Session session : visible) {
                tableModel.addRow(new Object[]{session.getId(), session.getTopic(), session.getMinutes()});
                shownMinutes += session.getMinutes();
            }
            totalLabel.setText("All sessions: " + ledger.listSessions().size()
                    + " | Total minutes: " + ledger.totalMinutes()
                    + " | Shown: " + visible.size() + " sessions, " + shownMinutes + " minutes"
                    + (activeTopic == null ? "" : " (filtered)"));
        } finally { updatingForm = false; }
        updateTitle();
    }

    /**
     * Updates the title and file label to distinguish unsaved data from unapplied form edits.
     */

    private void updateTitle() {
        frame.setTitle("Practice Ledger" + (dirty || formDirty ? " *" : ""));
        fileLabel.setText("File: " + currentFile.getFileName()
                + (dirty ? " | Unsaved ledger changes" : "")
                + (formDirty ? " | Form edits not applied" : ""));
        fileLabel.setToolTipText(currentFile.toString());
    }

    /**
     * Resolves the selected view row to its model row before reading its stable ID.
     * @return the selected session ID, or null when no row is selected
     */

    private Integer selectedId() {
        int row = table.getSelectedRow();
        return row < 0 ? null : (Integer) tableModel.getValueAt(table.convertRowIndexToModel(row), 0);
    }

    /**
     * Copies selected-row values into the form after checking for unapplied edits.
     * <p>If discarding edits is declined, the current form is preserved and the
     * table selection is cleared.</p>
     */

    private void fillFromSelection() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        if (!discardFormEdits()) {
            updatingForm = true;
            try { table.clearSelection(); } finally { updatingForm = false; }
            return;
        }
        int modelRow = table.convertRowIndexToModel(row);
        updatingForm = true;
        try {
            topicField.setText(tableModel.getValueAt(modelRow, 1).toString());
            minutesField.setText(tableModel.getValueAt(modelRow, 2).toString());
            formDirty = false;
        } finally { updatingForm = false; }
        updateTitle();
    }

    /**
     * Clears selection and form fields, resets draft tracking, and requests topic focus.
     * <p>This helper does not itself request confirmation.</p>
     */

    private void clearForm() {
        updatingForm = true;
        try {
            table.clearSelection(); topicField.setText(""); minutesField.setText("");
            formDirty = false;
        } finally { updatingForm = false; }
        topicField.requestFocusInWindow(); updateTitle();
    }

    /**
     * Asks whether unapplied form edits may be discarded, when any are present.
     * <p>This method only asks permission; it does not clear the fields.</p>
     * @return true if there are no draft edits or the user explicitly approves discarding them
     */

    private boolean discardFormEdits() {
        return !formDirty || JOptionPane.showConfirmDialog(frame,
                "The form has edits that have not been added or updated. Discard those edits?",
                "Unapplied form edits", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    /**
     * Adds a session or updates the selected session using the form values.
     * <p>Successful changes mark the ledger dirty and refresh the view. Validation
     * failures show a message and preserve the form for correction.</p>
     * @param edit true to update the selected ID; false to create a new session
     */

    private void submitForm(boolean edit) {
        Integer id = edit ? selectedId() : null;
        if (edit && id == null) { message("Select a session first."); return; }
        try {
            int minutes = Integer.parseInt(minutesField.getText().trim());
            if (edit) {
                if (!ledger.replaceSession(id, topicField.getText(), minutes)) {
                    message("Session not found."); return;
                }
            } else ledger.addSession(topicField.getText(), minutes);
            dirty = true;
            clearForm(); refreshView();
            statusLabel.setText((edit ? "Session updated." : "Session added.")
                    + (activeTopic == null ? "" : " A topic filter is active; use Show all if needed."));
        } catch (NumberFormatException e) { error("Enter a whole number within the int range."); }
        catch (IllegalArgumentException e) { error(e.getMessage()); }
    }

    /**
     * Deletes the selected session only after draft and deletion confirmations.
     * <p>A successful deletion marks the ledger dirty and refreshes the display.</p>
     */

    private void deleteSelected() {
        Integer id = selectedId();
        if (id == null) { message("Select a session first."); return; }
        if (!discardFormEdits()) return;
        if (JOptionPane.showConfirmDialog(frame, "Delete session #" + id + "?",
                "Delete session", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        if (ledger.deleteSession(id)) {
            dirty = true; clearForm(); refreshView(); statusLabel.setText("Session deleted.");
        } else message("Session not found.");
    }

    /**
     * Applies the entered exact-topic filter, ignoring case, and refreshes the view.
     * <p>Blank input displays a message rather than changing the current filter.</p>
     */

    private void applyFilter() {
        String topic = searchField.getText().trim();
        if (topic.isEmpty()) { message("Enter an exact topic, or use Show all."); return; }
        activeTopic = topic; refreshView();
        statusLabel.setText("Showing exact topic matches, ignoring capitalization.");
    }

    /**
     * Confirms and removes every session matching the topic entered in the filter field.
     * <p>The operation considers the whole ledger, not just the current selection.</p>
     */

    private void deleteTopic() {
        String topic = searchField.getText().trim();
        if (topic.isEmpty()) { message("Enter a topic in the Exact topic field first."); return; }
        int count = ledger.findSessionsByTopic(topic).size();
        if (count == 0) { message("No sessions match that topic."); return; }
        if (!discardFormEdits()) return;
        if (JOptionPane.showConfirmDialog(frame, "Delete all " + count + " sessions for '" + topic + "'?",
                "Delete topic", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        int deleted = ledger.deleteSessionsByTopic(topic);
        dirty = true; clearForm(); refreshView(); statusLabel.setText(deleted + " sessions deleted.");
    }

    /**
     * Displays the longest session in the whole ledger, or an empty-ledger message.
     */

    private void showLongest() {
        Session longest = ledger.findLongestSession();
        message(longest == null ? "No sessions yet." : "Longest session in the whole ledger:\n" + longest);
    }

    /**
     * Creates a JSON-oriented chooser initialized to the current file.
     * @return a configured file chooser
     */

    private JFileChooser fileChooser() {
        JFileChooser chooser = new JFileChooser(currentFile.getParent().toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("JSON files", "json"));
        chooser.setSelectedFile(currentFile.toFile());
        return chooser;
    }

    /**
     * Prompts for a destination, adds a missing JSON extension, and requests a save.
     */

    private void saveAs() {
        JFileChooser chooser = fileChooser();
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            target = target.resolveSibling(target.getFileName() + ".json");
        }
        saveTo(target, null);
    }

    /**
     * Requests a background save to the current file.
     * @param afterSave optional continuation run only after a successful save; null for none
     */

    private void saveCurrent(Runnable afterSave) { saveTo(currentFile, afterSave); }

    /**
     * Snapshots the ledger and saves it in the background after any overwrite confirmation.
     * <p>Only applied ledger data is saved; text still in the form is not included.
     * On success the current path is updated and ledger dirty tracking is cleared.</p>
     * @param target destination path
     * @param afterSave optional success continuation on the event dispatch thread
     */

    private void saveTo(Path target, Runnable afterSave) {
        if (Files.exists(target) && !(knownFile && target.equals(currentFile))) {
            if (JOptionPane.showConfirmDialog(frame, "Replace the existing file?\n" + target,
                    "Confirm save", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        }
        // Copy state on the GUI thread, then write that independent snapshot.
        PracticeLedger snapshot = new PracticeLedger();
        snapshot.restoreSessions(ledger.listSessions(), ledger.getNextId());
        runFileTask("Saving...", () -> {
            saveSafely(snapshot, target); return target;
        }, saved -> {
            currentFile = saved; knownFile = true; dirty = false; updateTitle();
            statusLabel.setText("Saved to " + saved);
            if (afterSave != null) afterSave.run();
        });
    }

    // Finish writing a temporary file before replacing the previous save.
    /**
     * Writes a complete temporary file before replacing the destination.
     * <p>The temporary file is created in the destination directory. Atomic replacement
     * is attempted; unsupported filesystems fall back to ordinary replacement, which
     * does not provide the same atomicity guarantee. Temporary-file cleanup is attempted
     * in all cases. The parent directory must already exist.</p>
     * @param snapshot independent ledger state to write
     * @param target destination file
     * @throws IOException if temporary-file creation, writing, replacement, or cleanup fails
     */

    static void saveSafely(PracticeLedger snapshot, Path target) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".ledger-", ".tmp");
        try {
            LedgerFileStore.save(snapshot, temporary);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * Prompts for a source file and protects current edits before requesting a load.
     */

    private void chooseLoad() {
        JFileChooser chooser = fileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath().toAbsolutePath();
        protectChanges(() -> loadFrom(source));
    }

    /**
     * Loads into a separate ledger on a worker, then commits valid state on the GUI thread.
     * <p>Successful loading resets the filter, form, and dirty state. Worker failures
     * leave the current ledger intact and are reported by the file-task handler.</p>
     * @param source JSON file to read
     */

    private void loadFrom(Path source) {
        runFileTask("Loading...", () -> {
            PracticeLedger loaded = new PracticeLedger();
            LedgerFileStore.load(loaded, source);
            return loaded;
        }, loaded -> {
            ledger.restoreSessions(loaded.listSessions(), loaded.getNextId());
            currentFile = source; knownFile = true; dirty = false;
            activeTopic = null; searchField.setText("");
            clearForm(); refreshView(); statusLabel.setText("Loaded " + source);
        });
    }

    /**
     * Runs a continuation only after draft and unsaved-ledger prompts permit it.
     * <p>Choosing Save schedules the continuation after successful saving. Cancel,
     * a dismissed prompt, or a failed save prevents the continuation.</p>
     * @param next action to perform when it is safe to proceed
     */

    private void protectChanges(Runnable next) {
        if (!discardFormEdits()) return;
        if (!dirty) { next.run(); return; }
        Object[] options = {"Save and continue", "Discard changes", "Cancel"};
        int answer = JOptionPane.showOptionDialog(frame,
                "The ledger has unsaved changes.", "Unsaved changes",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[2]);
        if (answer == 0) saveCurrent(next);
        else if (answer == 1) next.run();
    }

    /**
     * Requests window disposal after unsaved-change checks, or waits if file work is active.
     */

    private void requestClose() {
        if (busy) { statusLabel.setText("Please wait for the file operation to finish."); return; }
        protectChanges(frame::dispose);
    }

    /**
     * Runs file work off the event dispatch thread while controls are disabled.
     * <p>Completion handling returns to the event dispatch thread. Worker failures
     * are reported in a dialog; the success callback runs only if the task returns normally.</p>
     * @param <T> result type produced by the worker
     * @param status message displayed while the task is running
     * @param task background operation that must not modify Swing controls or the live ledger
     * @param success completion callback run on the event dispatch thread
     */

    private <T> void runFileTask(String status, Callable<T> task, Consumer<T> success) {
        setBusy(true); statusLabel.setText(status);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                setBusy(false);
                try { success.accept(get()); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); error("File operation interrupted.");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    error("File operation failed: " + cause.getMessage());
                    statusLabel.setText("File operation failed. Current sessions have been kept.");
                }
            }
        }.execute();
    }

    /**
     * Updates busy tracking, control availability, and the cursor.
     * @param value true while a file operation is active
     */

    private void setBusy(boolean value) {
        busy = value; enableTree(frame.getContentPane(), !value);
        frame.setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /**
     * Recursively enables or disables a component and its descendants.
     * @param component root of the component subtree
     * @param enabled whether components should accept interaction
     */

    private void enableTree(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) enableTree(child, enabled);
        }
    }

    /**
     * Displays an informational dialog owned by the ledger window.
     * @param text message to display
     */

    private void message(String text) { JOptionPane.showMessageDialog(frame, text); }
    /**
     * Displays an error dialog owned by the ledger window.
     * @param text explanation of the failure
     */

    private void error(String text) {
        JOptionPane.showMessageDialog(frame, text, "Practice Ledger", JOptionPane.ERROR_MESSAGE);
    }
    /**
     * Displays the constructed window.
     * <p>Call this method on the event dispatch thread.</p>
     */

    public void show() { frame.setVisible(true); }

    /**
     * Schedules construction and display of an empty ledger GUI on the event dispatch thread.
     * @param args command-line arguments; unused
     */

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new LedgerGui(new PracticeLedger()).show());
    }
}