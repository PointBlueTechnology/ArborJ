package com.pointbluetech.arborj.view;

import com.pointbluetech.arborj.controller.MainController;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual LDAP filter builder dialog with nested bordered groups.
 * Each AND/OR/NOT group is a bordered container that visually wraps all its children.
 */
public class FilterBuilderDialog extends Stage {

    private final MainController controller;
    private final ScrollPane scrollPane;
    private FilterNode rootNode;
    private String resultFilter = null;

    // ──────────────────────────────────────────────────────────────────────
    //  Filter Node Model
    // ──────────────────────────────────────────────────────────────────────

    /** Abstract base for all filter tree nodes. */
    public static abstract sealed class FilterNode
            permits ComparisonNode, AndNode, OrNode, NotNode {
        public abstract String toDisplayString();
    }

    /** Leaf: attribute op value comparison. */
    public static final class ComparisonNode extends FilterNode {
        private String attribute;
        private String operator; // =, >=, <=, ~=, =*
        private String value;

        public ComparisonNode() { this("", "=", ""); }

        public ComparisonNode(String attribute, String operator, String value) {
            this.attribute = attribute;
            this.operator = operator;
            this.value = value;
        }

        public String getAttribute()  { return attribute; }
        public void setAttribute(String attribute) { this.attribute = attribute; }
        public String getOperator()    { return operator; }
        public void setOperator(String operator) { this.operator = operator; }
        public String getValue()       { return value; }
        public void setValue(String value) { this.value = value; }

        @Override
        public String toDisplayString() {
            if ("=*".equals(operator)) {
                return attribute + "=*";
            }
            return attribute + operator + value;
        }
    }

    /** Compound: AND (&) group. */
    public static final class AndNode extends FilterNode {
        private final List<FilterNode> children = new ArrayList<>();

        public List<FilterNode> getChildren() { return children; }

        @Override
        public String toDisplayString() { return "AND"; }
    }

    /** Compound: OR (|) group. */
    public static final class OrNode extends FilterNode {
        private final List<FilterNode> children = new ArrayList<>();

        public List<FilterNode> getChildren() { return children; }

        @Override
        public String toDisplayString() { return "OR"; }
    }

    /** Unary: NOT (!) wrapper. */
    public static final class NotNode extends FilterNode {
        private FilterNode child;

        public NotNode() { this(new ComparisonNode()); }

        public NotNode(FilterNode child) { this.child = child; }

        public FilterNode getChild() { return child; }
        public void setChild(FilterNode child) { this.child = child; }

        @Override
        public String toDisplayString() { return "NOT"; }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Filter Parser
    // ──────────────────────────────────────────────────────────────────────

    /** Parses an LDAP filter string into a FilterNode tree. */
    public static final class FilterParser {

        /**
         * Parse an LDAP filter string.
         * @param filter the raw filter, e.g. {@code (&(cn=foo)(objectClass=*))}
         * @return parsed FilterNode tree
         * @throws IllegalArgumentException if the filter is malformed
         */
        public static FilterNode parse(String filter) {
            if (filter == null || filter.isBlank()) {
                throw new IllegalArgumentException("Empty filter");
            }
            filter = filter.trim();
            int[] pos = {0};
            FilterNode result = parseNode(filter, pos);
            if (pos[0] != filter.length()) {
                throw new IllegalArgumentException("Unexpected trailing content at position " + pos[0]);
            }
            return result;
        }

        private static FilterNode parseNode(String s, int[] pos) {
            expect(s, pos, '(');
            char next = s.charAt(pos[0]);
            FilterNode node;
            switch (next) {
                case '&' -> {
                    pos[0]++;
                    AndNode and = new AndNode();
                    while (pos[0] < s.length() && s.charAt(pos[0]) == '(') {
                        and.getChildren().add(parseNode(s, pos));
                    }
                    node = and;
                }
                case '|' -> {
                    pos[0]++;
                    OrNode or = new OrNode();
                    while (pos[0] < s.length() && s.charAt(pos[0]) == '(') {
                        or.getChildren().add(parseNode(s, pos));
                    }
                    node = or;
                }
                case '!' -> {
                    pos[0]++;
                    NotNode not = new NotNode(parseNode(s, pos));
                    node = not;
                }
                default -> {
                    node = parseComparison(s, pos);
                }
            }
            expect(s, pos, ')');
            return node;
        }

        private static ComparisonNode parseComparison(String s, int[] pos) {
            int start = pos[0];
            // Find the operator
            int i = start;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '=' || c == '~' || c == '>' || c == '<') break;
                if (c == ')') break;
                i++;
            }
            String attribute = s.substring(start, i);

            if (i >= s.length() || s.charAt(i) == ')') {
                throw new IllegalArgumentException("Missing operator in comparison at position " + i);
            }

            String operator;
            char opChar = s.charAt(i);
            if (opChar == '=') {
                operator = "=";
                i++;
            } else if ((opChar == '>' || opChar == '<' || opChar == '~') && i + 1 < s.length() && s.charAt(i + 1) == '=') {
                operator = "" + opChar + "=";
                i += 2;
            } else {
                throw new IllegalArgumentException("Invalid operator at position " + i);
            }

            // Read value until an unescaped closing paren. Per RFC 4515,
            // backslash starts a two-hex-digit escape, so a literal `)` in a
            // value appears as `\29` and must not terminate the value.
            int valStart = i;
            StringBuilder rawValue = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ')') break;
                if (c == '\\') {
                    if (i + 2 >= s.length()) {
                        throw new IllegalArgumentException(
                                "Truncated escape at position " + i);
                    }
                    int hi = Character.digit(s.charAt(i + 1), 16);
                    int lo = Character.digit(s.charAt(i + 2), 16);
                    if (hi < 0 || lo < 0) {
                        throw new IllegalArgumentException(
                                "Invalid \\xx escape at position " + i);
                    }
                    rawValue.append((char) ((hi << 4) | lo));
                    i += 3;
                } else {
                    rawValue.append(c);
                    i++;
                }
            }
            pos[0] = i;
            String value = rawValue.toString();

            // Detect presence filter: (attr=*)
            if ("=".equals(operator) && "*".equals(value) && valStart != i
                    && s.charAt(valStart) == '*') {
                return new ComparisonNode(attribute, "=*", "");
            }

            return new ComparisonNode(attribute, operator, value);
        }

        private static void expect(String s, int[] pos, char expected) {
            if (pos[0] >= s.length() || s.charAt(pos[0]) != expected) {
                throw new IllegalArgumentException(
                        "Expected '" + expected + "' at position " + pos[0]);
            }
            pos[0]++;
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Filter Serializer
    // ──────────────────────────────────────────────────────────────────────

    /** Serializes a FilterNode tree back to an LDAP filter string. */
    public static final class FilterSerializer {

        public static String serialize(FilterNode node) {
            if (node == null) return "";
            StringBuilder sb = new StringBuilder();
            serializeNode(node, sb);
            return sb.toString();
        }

        private static void serializeNode(FilterNode node, StringBuilder sb) {
            switch (node) {
                case ComparisonNode c -> {
                    sb.append('(');
                    if ("=*".equals(c.getOperator())) {
                        sb.append(c.getAttribute()).append("=*");
                    } else {
                        sb.append(c.getAttribute()).append(c.getOperator());
                        appendEscapedValue(sb, c.getValue());
                    }
                    sb.append(')');
                }
                case AndNode a -> {
                    sb.append("(&");
                    for (FilterNode child : a.getChildren()) {
                        serializeNode(child, sb);
                    }
                    sb.append(')');
                }
                case OrNode o -> {
                    sb.append("(|");
                    for (FilterNode child : o.getChildren()) {
                        serializeNode(child, sb);
                    }
                    sb.append(')');
                }
                case NotNode n -> {
                    sb.append("(!");
                    serializeNode(n.getChild(), sb);
                    sb.append(')');
                }
            }
        }

        /**
         * Escape a comparison value per RFC 4515 §3. Special characters
         * `\`, `*`, `(`, `)`, and NUL are written as `\xx` two-hex-digit
         * sequences so the filter round-trips unambiguously.
         */
        private static void appendEscapedValue(StringBuilder sb, String value) {
            if (value == null) return;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\\' -> sb.append("\\5c");
                    case '*'  -> sb.append("\\2a");
                    case '('  -> sb.append("\\28");
                    case ')'  -> sb.append("\\29");
                    case '\0' -> sb.append("\\00");
                    default   -> sb.append(c);
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Constructor & UI
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Create the filter builder dialog.
     *
     * @param controller the MainController (may be null; used for schema attribute autocomplete)
     * @param currentFilter the current LDAP filter string to edit, or null/empty for a fresh start
     */
    public FilterBuilderDialog(MainController controller, String currentFilter) {
        this(controller, currentFilter, null);
    }

    public FilterBuilderDialog(MainController controller, String currentFilter,
                               javafx.stage.Window owner) {
        this.controller = controller;
        initModality(Modality.APPLICATION_MODAL);
        if (owner != null) initOwner(owner);
        setTitle("LDAP Filter Builder");
        setResizable(true);

        // Parse the current filter, or create a default
        boolean validFilter;
        if (currentFilter != null && !currentFilter.isBlank()) {
            try {
                rootNode = FilterParser.parse(currentFilter.trim());
                validFilter = true;
            } catch (Exception e) {
                rootNode = createDefaultRoot();
                validFilter = false;
            }
        } else {
            rootNode = createDefaultRoot();
            validFilter = false;
        }

        // Ensure root is a compound node
        if (rootNode instanceof ComparisonNode) {
            AndNode wrapper = new AndNode();
            wrapper.getChildren().add(rootNode);
            rootNode = wrapper;
        }

        // ── Info banner ──
        Label banner = new Label();
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setPadding(new Insets(8, 12, 8, 12));
        if (validFilter) {
            banner.setText("Editing current filter");
            banner.setStyle("-fx-background-color: derive(green, 80%); -fx-text-fill: derive(green, -40%); -fx-font-weight: bold;");
        } else {
            banner.setText(currentFilter != null && !currentFilter.isBlank()
                    ? "Current filter is invalid \u2014 starting fresh"
                    : "New filter");
            banner.setStyle("-fx-background-color: derive(orange, 70%); -fx-text-fill: derive(orange, -40%); -fx-font-weight: bold;");
        }

        // ── ScrollPane with recursive layout ──
        scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setPadding(new Insets(8));
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        rebuildUI();

        // ── Bottom buttons ──
        MenuButton addToRootBtn = new MenuButton("Add to Root");
        MenuItem rootAddCond = new MenuItem("Condition");
        rootAddCond.setOnAction(_ -> { addChildToRoot(new ComparisonNode()); rebuildUI(); });
        MenuItem rootAddAnd = new MenuItem("AND Group");
        rootAddAnd.setOnAction(_ -> { AndNode a = new AndNode(); a.getChildren().add(new ComparisonNode()); addChildToRoot(a); rebuildUI(); });
        MenuItem rootAddOr = new MenuItem("OR Group");
        rootAddOr.setOnAction(_ -> { OrNode o = new OrNode(); o.getChildren().add(new ComparisonNode()); addChildToRoot(o); rebuildUI(); });
        MenuItem rootAddNot = new MenuItem("NOT");
        rootAddNot.setOnAction(_ -> { NotNode n = new NotNode(); n.setChild(new ComparisonNode()); addChildToRoot(n); rebuildUI(); });
        addToRootBtn.getItems().addAll(rootAddCond, rootAddAnd, rootAddOr, rootAddNot);

        Label hint = new Label("Right-click a group to add nested items");
        hint.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11;");

        HBox leftButtons = new HBox(8, addToRootBtn, hint);
        leftButtons.setAlignment(Pos.CENTER_LEFT);

        Button applyBtn = new Button("Apply");
        applyBtn.setDefaultButton(true);
        applyBtn.setOnAction(_ -> {
            resultFilter = FilterSerializer.serialize(rootNode);
            close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setCancelButton(true);
        cancelBtn.setOnAction(_ -> {
            resultFilter = null;
            close();
        });

        HBox rightButtons = new HBox(8, applyBtn, cancelBtn);
        rightButtons.setAlignment(Pos.CENTER_RIGHT);

        BorderPane bottomBar = new BorderPane();
        bottomBar.setLeft(leftButtons);
        bottomBar.setRight(rightButtons);
        bottomBar.setPadding(new Insets(8, 12, 12, 12));

        // ── Root layout ──
        VBox root = new VBox(banner, scrollPane, bottomBar);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 650, 550);
        setScene(scene);
    }

    /** Returns the serialized filter string, or null if the dialog was cancelled. */
    public String getFilter() {
        return resultFilter;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Default Root
    // ──────────────────────────────────────────────────────────────────────

    private static FilterNode createDefaultRoot() {
        AndNode root = new AndNode();
        root.getChildren().add(new ComparisonNode());
        return root;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  UI Rebuild
    // ──────────────────────────────────────────────────────────────────────

    /** Clears the scroll pane and rebuilds the entire UI from the model. */
    private void rebuildUI() {
        scrollPane.setContent(buildNodeUI(rootNode, null, -1));
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Recursive Layout Builder
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Build a JavaFX Node for the given FilterNode.
     * @param node       the filter node to render
     * @param parentNode the parent filter node (null for root)
     * @param indexInParent the index of this node in the parent's children (-1 for root)
     */
    private Node buildNodeUI(FilterNode node, FilterNode parentNode, int indexInParent) {
        return switch (node) {
            case ComparisonNode c -> buildComparisonUI(c, parentNode, indexInParent);
            case AndNode a -> buildGroupUI(a, parentNode, indexInParent);
            case OrNode o -> buildGroupUI(o, parentNode, indexInParent);
            case NotNode n -> buildNotUI(n, parentNode, indexInParent);
        };
    }

    /** Build the UI for a comparison (leaf) node. */
    private Node buildComparisonUI(ComparisonNode c, FilterNode parentNode, int indexInParent) {
        TextField attrField = new TextField(c.getAttribute());
        attrField.setPromptText("attribute");
        attrField.setPrefWidth(160);
        attrField.setFont(Font.font("monospaced", 14));
        attrField.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 3; -fx-background-radius: 3;");
        attrField.textProperty().addListener((_, _, newVal) -> c.setAttribute(newVal));

        if (controller != null) {
            setupAutocomplete(attrField);
        }

        ComboBox<String> opCombo = new ComboBox<>(
                FXCollections.observableArrayList("=", ">=", "<=", "~=", "=*"));
        opCombo.setValue(c.getOperator());
        opCombo.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");
        opCombo.setPrefWidth(75);

        TextField valueField = new TextField(c.getValue());
        valueField.setPromptText("value");
        valueField.setPrefWidth(160);
        valueField.setFont(Font.font("monospaced", 14));
        valueField.setStyle("-fx-border-color: -color-border-default; -fx-border-radius: 3; -fx-background-radius: 3;");
        valueField.textProperty().addListener((_, _, newVal) -> c.setValue(newVal));
        valueField.setVisible(!"=*".equals(c.getOperator()));
        valueField.setManaged(!"=*".equals(c.getOperator()));

        opCombo.setOnAction(_ -> {
            String op = opCombo.getValue();
            c.setOperator(op);
            boolean isPresence = "=*".equals(op);
            valueField.setVisible(!isPresence);
            valueField.setManaged(!isPresence);
            if (isPresence) {
                c.setValue("");
            }
        });

        // Delete button
        Button deleteBtn = new Button("\u00D7");
        deleteBtn.setStyle("-fx-font-size: 14; -fx-padding: 0 4 0 4; -fx-background-color: transparent; "
                + "-fx-text-fill: -color-danger-fg; -fx-cursor: hand;");
        deleteBtn.setOnAction(_ -> {
            removeChild(parentNode, indexInParent);
            rebuildUI();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(6, attrField, opCombo, valueField, spacer, deleteBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4, 8, 4, 8));
        box.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 4;");

        // Context menu
        ContextMenu menu = new ContextMenu();
        MenuItem wrapNot = new MenuItem("Wrap in NOT");
        wrapNot.setOnAction(_ -> {
            wrapChildInNot(parentNode, indexInParent);
            rebuildUI();
        });
        MenuItem remove = new MenuItem("Remove Condition");
        remove.setOnAction(_ -> {
            removeChild(parentNode, indexInParent);
            rebuildUI();
        });
        menu.getItems().addAll(wrapNot, remove);
        box.setOnContextMenuRequested(e -> {
            menu.show(box, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        return box;
    }

    /** Build the UI for an AND or OR group node. */
    private Node buildGroupUI(FilterNode groupNode, FilterNode parentNode, int indexInParent) {
        boolean isAnd = groupNode instanceof AndNode;
        List<FilterNode> children = isAnd
                ? ((AndNode) groupNode).getChildren()
                : ((OrNode) groupNode).getChildren();

        String colorPrefix = isAnd ? "accent" : "success";
        String labelText = isAnd ? "\u25A0 AND" : "\u25A0 OR";

        // Header label
        Label label = new Label(labelText);
        label.setFont(Font.font("System", FontWeight.BOLD, 16));
        label.setStyle("-fx-text-fill: -color-" + colorPrefix + "-fg;");

        // Add child button — MenuButton with all types
        MenuButton addBtn = new MenuButton("+");
        addBtn.setStyle("-fx-font-size: 14; -fx-font-weight: bold; -fx-padding: 0 6 0 6; "
                + "-fx-background-color: transparent; -fx-text-fill: -color-" + colorPrefix + "-fg; -fx-cursor: hand;");
        MenuItem addCond = new MenuItem("Condition");
        addCond.setOnAction(_ -> { children.add(new ComparisonNode()); rebuildUI(); });
        MenuItem addAndG = new MenuItem("AND Group");
        addAndG.setOnAction(_ -> { AndNode a = new AndNode(); a.getChildren().add(new ComparisonNode()); children.add(a); rebuildUI(); });
        MenuItem addOrG = new MenuItem("OR Group");
        addOrG.setOnAction(_ -> { OrNode o = new OrNode(); o.getChildren().add(new ComparisonNode()); children.add(o); rebuildUI(); });
        MenuItem addNotG = new MenuItem("NOT");
        addNotG.setOnAction(_ -> { NotNode n = new NotNode(); n.setChild(new ComparisonNode()); children.add(n); rebuildUI(); });
        addBtn.getItems().addAll(addCond, addAndG, addOrG, addNotG);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox header = new HBox(8, label, addBtn, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 8, 4, 8));

        // Context menu on header
        ContextMenu menu = buildGroupContextMenu(groupNode, parentNode, indexInParent, children);
        header.setOnContextMenuRequested(e -> {
            menu.show(header, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // Children container with left padding for indentation
        VBox childrenBox = new VBox();
        childrenBox.setPadding(new Insets(0, 0, 0, 16));

        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                // Thin separator between children
                Separator sep = new Separator();
                sep.setPadding(new Insets(2, 0, 2, 0));
                childrenBox.getChildren().add(sep);
            }
            childrenBox.getChildren().add(buildNodeUI(children.get(i), groupNode, i));
        }

        // Outer container with border
        VBox container = new VBox(4, header, childrenBox);
        container.setPadding(new Insets(6, 8, 8, 8));
        container.setStyle("-fx-border-color: -color-" + colorPrefix + "-fg; "
                + "-fx-background-color: -color-" + colorPrefix + "-subtle; "
                + "-fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6;");

        return container;
    }

    /** Build the UI for a NOT node. */
    private Node buildNotUI(NotNode notNode, FilterNode parentNode, int indexInParent) {
        // Header label
        Label label = new Label("\u25A0 NOT");
        label.setFont(Font.font("System", FontWeight.BOLD, 16));
        label.setStyle("-fx-text-fill: -color-danger-fg;");

        // Replace child button
        MenuButton replaceBtn = new MenuButton("+");
        replaceBtn.setStyle("-fx-font-size: 14; -fx-font-weight: bold; -fx-padding: 0 6 0 6; "
                + "-fx-background-color: transparent; -fx-text-fill: -color-danger-fg; -fx-cursor: hand;");
        MenuItem repCond = new MenuItem("Condition");
        repCond.setOnAction(_ -> { notNode.setChild(new ComparisonNode()); rebuildUI(); });
        MenuItem repAnd = new MenuItem("AND Group");
        repAnd.setOnAction(_ -> { AndNode a = new AndNode(); a.getChildren().add(new ComparisonNode()); notNode.setChild(a); rebuildUI(); });
        MenuItem repOr = new MenuItem("OR Group");
        repOr.setOnAction(_ -> { OrNode o = new OrNode(); o.getChildren().add(new ComparisonNode()); notNode.setChild(o); rebuildUI(); });
        replaceBtn.getItems().addAll(repCond, repAnd, repOr);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox header = new HBox(8, label, replaceBtn, headerSpacer);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 8, 4, 8));

        // Context menu on header
        ContextMenu menu = new ContextMenu();

        MenuItem addCond = new MenuItem("Replace with Condition");
        addCond.setOnAction(_ -> {
            notNode.setChild(new ComparisonNode());
            rebuildUI();
        });

        MenuItem addAndGroup = new MenuItem("Replace with AND Group");
        addAndGroup.setOnAction(_ -> {
            AndNode a = new AndNode();
            a.getChildren().add(new ComparisonNode());
            notNode.setChild(a);
            rebuildUI();
        });

        MenuItem addOrGroup = new MenuItem("Replace with OR Group");
        addOrGroup.setOnAction(_ -> {
            OrNode o = new OrNode();
            o.getChildren().add(new ComparisonNode());
            notNode.setChild(o);
            rebuildUI();
        });

        MenuItem removeGroup = new MenuItem("Remove Group");
        removeGroup.setOnAction(_ -> {
            if (parentNode != null) {
                removeChild(parentNode, indexInParent);
                rebuildUI();
            }
        });

        menu.getItems().addAll(addCond, addAndGroup, addOrGroup, new SeparatorMenuItem(), removeGroup);

        header.setOnContextMenuRequested(e -> {
            menu.show(header, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // Child container with left padding
        VBox childBox = new VBox();
        childBox.setPadding(new Insets(0, 0, 0, 16));
        if (notNode.getChild() != null) {
            childBox.getChildren().add(buildNodeUI(notNode.getChild(), notNode, 0));
        }

        // Outer container with danger border
        VBox container = new VBox(4, header, childBox);
        container.setPadding(new Insets(6, 8, 8, 8));
        container.setStyle("-fx-border-color: -color-danger-fg; "
                + "-fx-background-color: -color-danger-subtle; "
                + "-fx-border-width: 1; -fx-border-radius: 6; -fx-background-radius: 6;");

        return container;
    }

    /** Build context menu for AND/OR group headers. */
    private ContextMenu buildGroupContextMenu(FilterNode groupNode, FilterNode parentNode,
                                               int indexInParent, List<FilterNode> children) {
        ContextMenu menu = new ContextMenu();

        MenuItem addCond = new MenuItem("Add Condition");
        addCond.setOnAction(_ -> {
            children.add(new ComparisonNode());
            rebuildUI();
        });

        MenuItem addAnd = new MenuItem("Add AND Group");
        addAnd.setOnAction(_ -> {
            AndNode a = new AndNode();
            a.getChildren().add(new ComparisonNode());
            children.add(a);
            rebuildUI();
        });

        MenuItem addOr = new MenuItem("Add OR Group");
        addOr.setOnAction(_ -> {
            OrNode o = new OrNode();
            o.getChildren().add(new ComparisonNode());
            children.add(o);
            rebuildUI();
        });

        MenuItem addNot = new MenuItem("Add NOT");
        addNot.setOnAction(_ -> {
            children.add(new NotNode(new ComparisonNode()));
            rebuildUI();
        });

        menu.getItems().addAll(addCond, addAnd, addOr, addNot);

        // Change type options
        menu.getItems().add(new SeparatorMenuItem());

        if (groupNode instanceof OrNode) {
            MenuItem changeToAnd = new MenuItem("Change to AND");
            changeToAnd.setOnAction(_ -> {
                AndNode replacement = new AndNode();
                replacement.getChildren().addAll(children);
                replaceNode(parentNode, indexInParent, replacement);
                rebuildUI();
            });
            menu.getItems().add(changeToAnd);
        }
        if (groupNode instanceof AndNode) {
            MenuItem changeToOr = new MenuItem("Change to OR");
            changeToOr.setOnAction(_ -> {
                OrNode replacement = new OrNode();
                replacement.getChildren().addAll(children);
                replaceNode(parentNode, indexInParent, replacement);
                rebuildUI();
            });
            menu.getItems().add(changeToOr);
        }

        // Remove group (not available for root)
        if (parentNode != null) {
            menu.getItems().add(new SeparatorMenuItem());
            MenuItem removeGroup = new MenuItem("Remove Group");
            removeGroup.setOnAction(_ -> {
                removeChild(parentNode, indexInParent);
                rebuildUI();
            });
            menu.getItems().add(removeGroup);
        }

        return menu;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Model Manipulation
    // ──────────────────────────────────────────────────────────────────────

    /** Add a child to the root node (must be AND or OR). */
    private void addChildToRoot(FilterNode child) {
        switch (rootNode) {
            case AndNode a -> a.getChildren().add(child);
            case OrNode o -> o.getChildren().add(child);
            default -> { /* root should always be compound */ }
        }
    }

    /** Remove a child at the given index from the parent node. */
    private void removeChild(FilterNode parentNode, int index) {
        if (parentNode == null || index < 0) return;
        switch (parentNode) {
            case AndNode a -> { if (index < a.getChildren().size()) a.getChildren().remove(index); }
            case OrNode o -> { if (index < o.getChildren().size()) o.getChildren().remove(index); }
            case NotNode n -> n.setChild(null);
            default -> {}
        }
    }

    /** Replace a child at the given index in the parent, or replace the root. */
    private void replaceNode(FilterNode parentNode, int index, FilterNode replacement) {
        if (parentNode == null) {
            // Replacing root
            rootNode = replacement;
            return;
        }
        switch (parentNode) {
            case AndNode a -> { if (index >= 0 && index < a.getChildren().size()) a.getChildren().set(index, replacement); }
            case OrNode o -> { if (index >= 0 && index < o.getChildren().size()) o.getChildren().set(index, replacement); }
            case NotNode n -> n.setChild(replacement);
            default -> {}
        }
    }

    /** Wrap the child at the given index in a NOT node. */
    private void wrapChildInNot(FilterNode parentNode, int index) {
        if (parentNode == null || index < 0) return;
        switch (parentNode) {
            case AndNode a -> {
                if (index < a.getChildren().size()) {
                    a.getChildren().set(index, new NotNode(a.getChildren().get(index)));
                }
            }
            case OrNode o -> {
                if (index < o.getChildren().size()) {
                    o.getChildren().set(index, new NotNode(o.getChildren().get(index)));
                }
            }
            case NotNode n -> n.setChild(new NotNode(n.getChild()));
            default -> {}
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Autocomplete
    // ──────────────────────────────────────────────────────────────────────

    private void setupAutocomplete(TextField field) {
        ContextMenu suggestions = new ContextMenu();
        field.textProperty().addListener((_, _, newVal) -> {
            suggestions.hide();
            if (newVal == null || newVal.length() < 2) return;
            try {
                var attrMap = controller.getSchemaService().getAttributeMap();
                if (attrMap == null) return;
                String lower = newVal.toLowerCase();
                List<String> matches = attrMap.keySet().stream()
                        .filter(name -> name.toLowerCase().startsWith(lower))
                        .sorted()
                        .limit(10)
                        .toList();
                if (matches.isEmpty()) return;
                suggestions.getItems().clear();
                for (String match : matches) {
                    MenuItem mi = new MenuItem(match);
                    mi.setOnAction(_ -> {
                        field.setText(match);
                        field.positionCaret(match.length());
                    });
                    suggestions.getItems().add(mi);
                }
                suggestions.show(field, Side.BOTTOM, 0, 0);
            } catch (Exception _) {
                // Schema not available; skip autocomplete
            }
        });
    }
}
