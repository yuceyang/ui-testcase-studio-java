package com.yuceyang.uitest.ui;

import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout.Orientation;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.UploadI18N;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinSession;
import com.yuceyang.uitest.ai.AiSettings;
import com.yuceyang.uitest.ai.ElementAdvisor;
import com.yuceyang.uitest.ai.RecognitionHistoryStore;
import com.yuceyang.uitest.ai.TreeAdvisor;
import com.yuceyang.uitest.casegen.CaseGenerationService;
import com.yuceyang.uitest.element.ElementCandidate;
import com.yuceyang.uitest.export.CaseExporter;
import com.yuceyang.uitest.project.WorkspaceService;
import com.yuceyang.uitest.screenshot.Screenshot;
import com.yuceyang.uitest.screenshot.ScreenshotFileStore;
import com.yuceyang.uitest.screenshot.ScreenshotStatus;
import com.yuceyang.uitest.storage.ProjectSummary;
import com.yuceyang.uitest.storage.StorageException;
import com.yuceyang.uitest.storage.WorkspaceSnapshot;
import com.yuceyang.uitest.system.SystemUnderTest;
import com.yuceyang.uitest.system.TestEnvironment;
import com.yuceyang.uitest.testcase.TestCase;
import com.yuceyang.uitest.tree.NodeType;
import com.yuceyang.uitest.tree.TreeNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;

@Route("")
@PageTitle("软件功能测试系统")
@CssImport("./styles/workspace.css")
public class WorkspaceView extends VerticalLayout {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkspaceView.class);
    private final transient WorkspaceService workspaceService;
    private final transient AiSettings aiSettings;
    private final ComboBox<ProjectSummary> projectSelect = new ComboBox<>();
    private final ComboBox<SystemUnderTest> systemSelect = new ComboBox<>();
    private final ComboBox<TestEnvironment> environmentSelect = new ComboBox<>();
    private final CaseGenerationService caseGenerationService;
    private final ElementAdvisor elementAdvisor;
    private final TreeAdvisor treeAdvisor;
    private final CaseExporter caseExporter;
    private final ScreenshotFileStore screenshotFileStore;
    /** 最近识别记录：关弹窗/刷页面后还能把识别结果找回来。 */
    private final RecognitionHistoryStore recognitionHistory;
    private final FunctionTree tree = new FunctionTree(new FunctionTree.Handler() {
        @Override public void toggle(String id) { treeToggle(id); }
        @Override public void select(String id, boolean ctrl, boolean shift) { treeSelect(id, ctrl, shift); }
        @Override public void check(String id) { treeCheck(id); }
        @Override public void open(String id) { treeOpen(id); }
        @Override public void move(String draggedId, String targetId) { treeMove(draggedId, targetId); }
        @Override public void reorder(String draggedId, String targetId, String zone) { treeDrop(draggedId, targetId, zone); }
        @Override public void menu(String id, double x, double y) { openTreeMenu(id, x, y); }
        @Override public void menuPick(String id, String action) { treeMenuPick(id, action); }
    });
    private final Set<String> collapsedTreeIds = new HashSet<>();
    /** 大项目第一次打开时自动收起的系统：每个系统只自动收一次，之后记住用户的手动展开。 */
    private final Set<String> autoCollapsedSystems = new HashSet<>();
    /** 正在加载项目：此时 systemSelect 的值变化不要再触发一次整树刷新。 */
    private boolean loadingProject;
    private String lastSelectedTreeId;
    private final Checkbox genNegative = new Checkbox("异常/边界");
    private final Checkbox genAuth = new Checkbox("鉴权");
    private final IntegerField genMin = new IntegerField("下限");
    private final IntegerField genMax = new IntegerField("上限");
    private final TextArea genExtra = new TextArea("额外要求");
    private final Span genScope = new Span("范围：全部功能点");
    private boolean generatingCases;
    private boolean aiChecking;
    private final TextField address = new TextField();
    /** 地址栏当前对应哪套「系统｜环境」；换环境才回填缓存地址，同环境下不覆盖用户手输的内容。 */
    private String addressContextKey;
    private final Span browserStatus = badge("未启动");
    private final Span saveStatus = new Span("操作后自动保存");
    private final Checkbox autoShot = autoScreenshot();
    private final VerticalLayout treeBody = new VerticalLayout();
    private final Div browserCanvas = new Div();
    private final Div mirrorWrap = new Div();
    private final Div mirrorOverlay = new Div();
    private int mirrorWidth = 1440;
    /** 镜像“拖拽交互”开关：开启后左键拖动原样转发到页面（滑块验证码等），关闭时拖动=框选元素。 */
    private boolean mirrorDragMode;
    private Button dragModeButton;
    private int mirrorHeight = 900;
    private final MirrorImage browserImage;
    private final MirrorInput mirrorInput;
    private final Div casePreview = new Div();
    private final Grid<ElementCandidate> elementGrid = new Grid<>();
    private final TextField elementFilter = new TextField();
    private final ComboBox<String> elementKind = new ComboBox<>();
    private final Span elementCount = new Span("0");
    private final Span treeCaseCount = new Span("0 用例");
    private final Span treeStats = new Span();
    /** 点选/连选/勾选出来的范围：「已选 3 个节点 + 2 条用例」。 */
    private final Span treeSelection = new Span();
    private final Set<String> selectedElementIds = new HashSet<>();
    /** 交互元素表头的全选框；勾选状态跟着下面可勾的行走。 */
    private Checkbox elementSelectAll;
    /** 会话级错误处理只装一次。 */
    private boolean errorHandlerInstalled;
    /** 元素 id → AI/规则给出的预筛建议（是否建议测试、归并组名、理由）。 */
    private final Map<String, ElementAdvisor.Hint> elementHints = new LinkedHashMap<>();
    private final Set<String> selectedTreeIds = new HashSet<>();
    /** 勾选框集合（级联）：与「点击选中」分开，勾一个上级会带上全部子孙。 */
    private final Set<String> checkedTreeIds = new LinkedHashSet<>();
    /**
     * 最后一次用的是哪种选法：勾选框还是点选（Ctrl/Shift 也算）。
     * 两种选法各留一份集合，但批量操作只认最后动过的那一份，
     * 否则「勾选框里还留着上次生成出来的节点」会把 Ctrl 点选的结果盖掉。
     */
    private boolean checkMode;
    /** 用户手动取消勾选的节点：子孙全勾时也不再自动勾上，删除就只删勾选的叶子。 */
    private final Set<String> explicitlyUnchecked = new LinkedHashSet<>();
    /** 半选（部分子孙被勾）的节点，只用于勾选框的 indeterminate 显示。 */
    private final Set<String> indeterminateTreeIds = new HashSet<>();
    private boolean rootAllChecked;
    private boolean rootHalfChecked;
    private List<ElementCandidate> discoveredElements = new ArrayList<>();
    /** 当前元素列表属于哪个被测系统；切换系统才重新从工作区加载，避免刷新树时把发现结果顶掉。 */
    private String elementSystemId;
    private List<TestCase> pendingCases = new ArrayList<>();
    /** 与 AI 生成同时进行的「显示项」用例：混批时先攒着，等 AI 结果回来一起进生成台。 */
    private List<TestCase> pendingDisplayCases = new ArrayList<>();
    private TreeNode selectedNode;
    private String selectedCaseId;
    private final Map<String, List<TreeNode>> children = new HashMap<>();
    private final List<TreeNode> roots = new ArrayList<>();
    private WorkspaceSnapshot workspace;
    private String sessionId;
    private String sessionSystemId;
    private String sessionEnvironmentId;
    private boolean collapsed;
    private int zoomPercent = 100;

    public WorkspaceView(WorkspaceService workspaceService, AiSettings aiSettings,
                         CaseGenerationService caseGenerationService, ElementAdvisor elementAdvisor,
                         TreeAdvisor treeAdvisor, CaseExporter caseExporter, ScreenshotFileStore screenshotFileStore,
                         RecognitionHistoryStore recognitionHistory) {
        this.workspaceService = workspaceService;
        this.aiSettings = aiSettings;
        this.caseGenerationService = caseGenerationService;
        this.elementAdvisor = elementAdvisor;
        this.treeAdvisor = treeAdvisor;
        this.caseExporter = caseExporter;
        this.screenshotFileStore = screenshotFileStore;
        this.recognitionHistory = recognitionHistory;
        this.browserImage = new MirrorImage(new MirrorImage.Handler() {
            @Override public void click(double x, double y, int button, int clickCount) { forwardClick(x, y, button, clickCount); }
            @Override public void wheel(double deltaX, double deltaY) { forwardWheel(deltaX, deltaY); }
            @Override public void mouse(String phase, double x, double y) { forwardMouse(phase, x, y); }
            @Override public void key(String key, String text, boolean control, boolean alt, boolean shift) { forwardKey(key, text, control, alt, shift); }
            @Override public void marquee(double x1, double y1, double x2, double y2, boolean additive) { selectByMarquee(x1, y1, x2, y2, additive); }
            @Override public void toggleBox(String elementId) { toggleElementSelection(elementId); }
            @Override public void sync() { syncMirror(); }
        });
        this.mirrorInput = new MirrorInput(new MirrorInput.Handler() {
            @Override public void insert(String text) {
                if (sessionId == null) return;
                try { workspaceService.insertText(sessionId, text); } catch (RuntimeException e) { showError(e); }
            }
            @Override public void press(String key) {
                if (sessionId == null) return;
                try { forwardKeyCombo(key); } catch (RuntimeException e) { showError(e); }
            }
            @Override public String copy() {
                if (sessionId == null) return "";
                try { return workspaceService.selectionText(sessionId); } catch (RuntimeException e) { showError(e); return ""; }
            }
            @Override public void sync() { syncMirror(); }
        });
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        addClassName("qts-app");
        getElement().setAttribute("lang", "zh-CN");
        add(header());
        SplitLayout main = new SplitLayout(leftPane(), rightPane(), Orientation.HORIZONTAL);
        main.setSizeFull();
        main.setSplitterPosition(29);
        main.addClassName("workspace-split");
        add(main);
        expand(main);
        installErrorHandler();
        refreshProjects();
    }

    /** 拖动分隔条 = 改它上方那块面板的像素高度；列高随内容增长，右栏负责滚动。 */
    private static final String PANEL_RESIZER_JS = """
            const stack = this;
            stack.style.overflow = 'visible';
            if (stack.__qtsResizer) return;
            stack.__qtsResizer = true;
            let drag = null;
            const MIN = 150;
            const PAD = 12; // 上下 padding
            const panels = () => [...stack.children]
                .filter(child => !child.classList.contains('right-splitter'));
            // 面板用像素高度，容器高度必须跟着算，否则列永远只有一屏高。
            const syncStack = () => {
              const total = [...stack.children]
                .reduce((sum, child) => sum + child.getBoundingClientRect().height, 0) + PAD;
              const paneHeight = stack.parentElement ? stack.parentElement.clientHeight : 0;
              stack.style.height = Math.max(paneHeight, Math.round(total)) + 'px';
            };
            // 首次布局完成后按可视高度给三块面板算像素高度：之后拖动只增不减，
            // 也不会因为容器变高又回头给面板分配空间（那会越拖越高的死循环）。
            const apply = () => {
              const pane = stack.parentElement;
              const paneHeight = pane ? pane.clientHeight : 0;
              const items = panels();
              if (paneHeight < 200 || items.length < 3) return false;
              if (stack.__qtsSized) return true;
              // 可视高度减掉内边距与两条分隔条，剩下的按 46% / 余下分给元素区与用例区。
              const avail = Math.max(480, paneHeight - PAD - 12 - 2);
              const browserH = Math.max(240, Math.round(avail * 0.46));
              const rest = Math.max(440, avail - browserH);
              const casesH = Math.max(260, Math.round(rest * 0.5));
              const elementsH = Math.max(200, rest - casesH);
              [[items[0], browserH], [items[1], elementsH], [items[2], casesH]].forEach(entry => {
                entry[0].style.flex = '0 0 auto';
                entry[0].style.height = entry[1] + 'px';
              });
              stack.__qtsSized = true;
              syncStack();
              return true;
            };
            stack.__qtsSync = syncStack;
            let bootTries = 0;
            const boot = () => {
              if (apply() || ++bootTries > 180) return;
              requestAnimationFrame(boot);
            };
            requestAnimationFrame(boot);
            window.addEventListener('resize', syncStack);
            stack.addEventListener('pointerdown', (e) => {
              const handle = e.target.closest('.right-splitter');
              if (!handle || !stack.contains(handle)) return;
              const panel = handle.previousElementSibling;
              if (!panel) return;
              drag = {panel, startY: e.clientY, startH: panel.getBoundingClientRect().height};
              handle.classList.add('dragging');
              document.body.style.userSelect = 'none';
              e.preventDefault();
            });
            window.addEventListener('pointermove', (e) => {
              if (!drag) return;
              const height = Math.max(MIN, drag.startH + (e.clientY - drag.startY));
              drag.panel.style.flex = '0 0 auto';
              drag.panel.style.height = height + 'px';
              syncStack();
            });
            window.addEventListener('pointerup', () => {
              if (!drag) return;
              drag = null;
              document.body.style.userSelect = '';
              stack.querySelectorAll('.right-splitter.dragging')
                   .forEach(h => h.classList.remove('dragging'));
            });
            """;

    /**
     * 交互元素表格的列宽可拖动：Flow 的 Grid 没暴露列宽拖拽开关，
     * 但底层 vaadin-grid 支持（表头右侧会出现 resize-handle），这里直接给列打开。
     */
    private static final String COLUMN_RESIZE_JS = """
            const grid = this;
            if (grid.__qtsColResize) return;
            grid.__qtsColResize = true;
            let tries = 0;
            const apply = () => {
              const columns = [...grid.querySelectorAll('vaadin-grid-column')];
              if (!columns.length) return false;
              columns.forEach(column => {
                if (!column.resizable) column.resizable = true;
                else if (column._headerCell
                         && !column._headerCell.querySelector('[part~="resize-handle"]')
                         && typeof column._resizableChanged === 'function') {
                  column._resizableChanged(true, column._headerCell);
                }
              });
              const handles = grid.shadowRoot
                ? grid.shadowRoot.querySelectorAll('[part~="resize-handle"]').length : 0;
              return handles > 0 || columns.every(column => column.resizable);
            };
            const tick = () => {
              if (apply() || ++tries > 40) return;
              setTimeout(tick, 150);
            };
            tick();
            """;

    private Component header() {
        projectSelect.setPlaceholder("选择项目");
        projectSelect.setItemLabelGenerator(ProjectSummary::name);
        projectSelect.setWidth("250px");
        projectSelect.addValueChangeListener(event -> loadProject(event.getValue()));
        Button load = button("加载", VaadinIcon.REFRESH, () -> loadProject(projectSelect.getValue()));
        Button create = button("新建", VaadinIcon.PLUS, this::openProjectDialog);
        Button delete = button("删除项目", VaadinIcon.TRASH, this::confirmProjectDelete);
        saveStatus.addClassName("muted");

        HorizontalLayout projectBar = new HorizontalLayout(new Span("项目"), projectSelect, create, load, delete, saveStatus);
        projectBar.setAlignItems(Alignment.CENTER);
        projectBar.addClassName("project-bar");

        HorizontalLayout actions = new HorizontalLayout(
                button("关闭浏览器", VaadinIcon.CLOSE_CIRCLE, this::closeBrowser),
                primary("发现元素", VaadinIcon.SEARCH, this::discoverElements),
                button("自动建树", VaadinIcon.SITEMAP, this::openAutoTreePreview),
                autoShot, button("手动截图", VaadinIcon.CAMERA, this::capture),
                button("最近识别", VaadinIcon.CLOCK, this::openRecognitionHistory),
                button("截图库", VaadinIcon.PICTURE, this::openScreenshotLibrary),
                primary("生成用例", VaadinIcon.FILE_TEXT, this::generateCases),
                button("模型", VaadinIcon.COG, this::openModelSettings), browserStatus);
        actions.addClassName("top-actions");
        actions.setAlignItems(Alignment.CENTER);
        HorizontalLayout header = new HorizontalLayout(projectBar, actions);
        header.addClassName("topbar");
        header.setWidthFull();
        header.expand(projectBar);
        return header;
    }

    private Component leftPane() {
        VerticalLayout pane = new VerticalLayout();
        pane.setPadding(false);
        pane.setSpacing(false);
        pane.setSizeFull();
        pane.addClassName("pane");
        pane.addClassName("left-pane");

        HorizontalLayout systemBar = new HorizontalLayout();
        systemBar.addClassName("system-bar");
        systemSelect.setPlaceholder("选择被测系统");
        systemSelect.setItemLabelGenerator(SystemUnderTest::getName);
        systemSelect.addValueChangeListener(event -> {
            if (!loadingProject) refreshTree();
        });
        Button addSystem = iconButton("添加系统", VaadinIcon.PLUS, this::openSystemDialog);
        Button addEnvironment = iconButton("添加环境", VaadinIcon.GLOBE, this::openEnvironmentDialog);
        systemBar.add(systemSelect, addSystem, addEnvironment);
        systemBar.expand(systemSelect);
        environmentSelect.setPlaceholder("选择环境");
        environmentSelect.setItemLabelGenerator(TestEnvironment::getName);
        environmentSelect.setWidthFull();
        // 换环境就把地址栏换成这套环境上次去的页面，不用再手输地址。
        environmentSelect.addValueChangeListener(event -> applyCachedAddress());
        pane.add(systemBar, environmentSelect);

        VerticalLayout treePanel = panel();
        HorizontalLayout treeHead = panelTitle("功能点树", treeCaseCount);
        treeHead.addClassName("tree-head");
        treeHead.addComponentAtIndex(1, helpIcon(
                "层级：模块 → 一/二/三级 → 功能点 → 用例（功能点可挂在任意节点下，功能点下只放用例）",
                "右键节点/用例：改名、改类型、移动、删除",
                "勾选框三下循环：勾满子孙 → 只取消自己（子孙仍勾着）→ 全部取消",
                "Ctrl 点选 / Shift 连选（节点和用例行都能选）· 拖到行中间=换层级，拖到行上/下边缘=调整排序",
                "用例超过 400 条的项目，首次打开只展开到一级目录（点行首 ▸ 展开下一层）"));
        treeHead.add(button("新增", VaadinIcon.PLUS, this::openNodeDialog));
        Div treeActions = new Div(
                button("改类型", VaadinIcon.EDIT, this::batchChangeType),
                button("移动到", VaadinIcon.SHARE, this::openMoveDialog),
                button("删除", VaadinIcon.TRASH, this::batchDeleteNodes),
                button("清理重复", VaadinIcon.ERASER, this::cleanDuplicateNodes),
                button("结构整理", VaadinIcon.WRENCH, this::openTreeRepairDialog),
                button("导出", VaadinIcon.DOWNLOAD, this::exportCases),
                button("编号核对", VaadinIcon.CALC, this::openCaseNumberAudit),
                button("折叠", VaadinIcon.COMPRESS_SQUARE, this::toggleTree));
        treeActions.addClassName("panel-actions");
        treeBody.setPadding(false);
        treeBody.setSpacing(false);
        treeBody.addClassName("tree-body");
        tree.setWidthFull();
        treeBody.add(tree);
        treeStats.addClassName("ft-stats");
        treeSelection.addClassName("ft-selection");
        treeSelection.setVisible(false);
        treePanel.add(treeHead, treeActions, treeStats, treeSelection, treeBody);
        treePanel.expand(treeBody);

        pane.add(treePanel);
        pane.expand(treePanel);
        return pane;
    }

    /**
     * 右栏三块面板 + 两条可拖分隔条。
     * 高度用行内 px 直接改（不用 SplitLayout 的百分比），所以往下拖可以把浏览器/元素区拉得比屏幕还高，
     * 整列随之变高、由右栏滚动条兜底——这就是“总高度可以无限拉高”的落点。
     */
    private Component rightPane() {
        VerticalLayout pane = new VerticalLayout();
        pane.setPadding(false);
        pane.setSpacing(false);
        pane.setSizeFull();
        pane.addClassName("pane");
        pane.addClassName("right-pane");
        VerticalLayout stack = new VerticalLayout(browserPanel(), panelResizer(),
                elementsPanel(), panelResizer(), casesPanel());
        stack.setPadding(false);
        stack.setSpacing(false);
        stack.addClassName("right-stack");
        stack.getElement().executeJs(PANEL_RESIZER_JS);
        pane.add(stack);
        return pane;
    }

    private Div panelResizer() {
        Div handle = new Div();
        handle.addClassName("right-splitter");
        handle.getElement().setAttribute("title", "上下拖动调整面板高度");
        return handle;
    }

    private Component browserPanel() {
        VerticalLayout panel = panel();
        panel.addClassName("browser-panel");
        address.setPlaceholder("输入目标系统地址后按回车或点击“前往”");
        address.setWidthFull();
        address.addKeyPressListener(Key.ENTER, event -> navigate());
        HorizontalLayout chrome = new HorizontalLayout(
                iconButton("后退", VaadinIcon.ARROW_LEFT, () -> browserAction("后退")),
                iconButton("刷新", VaadinIcon.REFRESH, () -> browserAction("刷新")), address,
                primary("前往", VaadinIcon.PLAY, this::navigate),
                iconButton("弹出窗口", VaadinIcon.EXTERNAL_LINK, this::openInNewTab),
                iconButton("最大化浏览器", VaadinIcon.EXPAND_SQUARE, this::maximizeBrowser),
                dragModeButton(),
                iconButton("缩小", VaadinIcon.MINUS, () -> zoom(-10)),
                iconButton("重置缩放", VaadinIcon.REFRESH, () -> zoom(0)),
                iconButton("放大", VaadinIcon.PLUS, () -> zoom(10)));
        chrome.addClassName("browser-chrome");
        chrome.setAlignItems(Alignment.CENTER);
        chrome.expand(address);

        browserCanvas.addClassName("browser-canvas");
        VerticalLayout empty = new VerticalLayout(new Icon(VaadinIcon.DESKTOP), new Span("输入网址后点击“前往”"),
                new Span("浏览器启动后可在此查看镜像、标记元素和框选截图"));
        empty.setAlignItems(Alignment.CENTER);
        empty.addClassName("browser-empty");
        browserCanvas.add(empty);
        panel.add(chrome, browserCanvas,
                hint("左键点击 · 右键菜单 · 拖拽框选元素（开「拖拽交互」后改为直接拖动页面，用于滑块验证码/拖拽排序）"
                        + " · 虚线框=未标记 / 绿色=已标记 / 橙色=选中 · Ctrl+点击框可反选"));
        panel.expand(browserCanvas);
        return panel;
    }

    private Component elementsPanel() {
        VerticalLayout panel = panel();
        panel.addClassName("elements-panel");
        HorizontalLayout head = panelTitle("交互元素", elementCount);
        head.addClassName("elements-head");
        elementFilter.setPlaceholder("过滤名称或定位信息");
        elementFilter.setWidth("170px");
        elementFilter.setClearButtonVisible(true);
        elementFilter.addValueChangeListener(event -> applyElementFilter());
        elementKind.setItems("全部类型", "按钮", "输入框", "下拉框", "链接", "勾选", "上传", "图表", "其他");
        elementKind.setValue("全部类型");
        elementKind.setWidth("110px");
        elementKind.addValueChangeListener(event -> applyElementFilter());
        head.add(elementFilter, elementKind, button("全选", null, this::selectAllElements),
                button("全不选", null, this::clearElementSelection),
                button("清空列表", VaadinIcon.ERASER, this::clearDiscoveredElements),
                button("清除所有标记", VaadinIcon.REPLY, this::clearAllMarks),
                button("AI 勾选", VaadinIcon.MAGIC, this::aiCheckElements),
                primary("批量标记", VaadinIcon.TAGS, this::openBulkMarkDialog));
        elementGrid.setHeightFull();
        elementGrid.getElement().executeJs(COLUMN_RESIZE_JS);
        elementGrid.addComponentColumn(this::elementCheckbox).setHeader(elementSelectAllHeader())
                .setWidth("76px").setFlexGrow(0);
        elementGrid.addColumn(ElementCandidate::getName).setHeader("名称").setAutoWidth(true);
        elementGrid.addColumn(this::kindName).setHeader("类型").setWidth("88px").setFlexGrow(0);
        elementGrid.addComponentColumn(this::elementAdvice).setHeader("预筛建议").setWidth("104px").setFlexGrow(0);
        elementGrid.addColumn(ElementCandidate::getSelector).setHeader("定位信息").setAutoWidth(true);
        elementGrid.addComponentColumn(item -> {
            Span mark = new Span(item.isConfirmed() ? "已标记" : "未标记");
            mark.addClassName("mark-state");
            if (item.isConfirmed()) mark.addClassName("marked");
            return mark;
        }).setHeader("标记").setWidth("70px").setFlexGrow(0);
        elementGrid.addComponentColumn(item -> button("编辑", VaadinIcon.EDIT, () -> openElementEditor(item)))
                .setHeader("操作").setWidth("76px").setFlexGrow(0);
        panel.add(head, elementGrid);
        panel.expand(elementGrid);
        return panel;
    }

    private Component casesPanel() {
        VerticalLayout panel = panel();
        panel.addClassName("cases-panel");
        HorizontalLayout head = panelTitle("用例生成台（未入库）", new Span("确认后保存"));
        head.add(button("生成预览", VaadinIcon.MAGIC, this::generateCases),
                primary("确认入库", VaadinIcon.DATABASE, this::confirmCases),
                button("改挂载", VaadinIcon.SHARE, this::openPendingCaseMoveDialog),
                button("导出", VaadinIcon.DOWNLOAD, this::exportCases),
                button("清空", VaadinIcon.TRASH, this::clearPendingCases));
        genNegative.setValue(true);
        genAuth.setValue(false);
        genMin.setValue(3);
        genMax.setValue(8);
        genMin.setWidth("92px");
        genMax.setWidth("92px");
        genMin.setStepButtonsVisible(true);
        genMax.setStepButtonsVisible(true);
        genExtra.setPlaceholder("可选：额外人工要求（如重点验证分页、只核对导出按钮可见性）");
        genExtra.setWidthFull();
        genExtra.setHeight("72px");
        genScope.addClassName("muted");
        HorizontalLayout options = new HorizontalLayout(genScope, genNegative, genAuth,
                new Span("随机条数"), genMin, new Span("~"), genMax, genExtra);
        options.setAlignItems(Alignment.CENTER);
        options.setWidthFull();
        options.addClassName("case-gen-options");
        options.expand(genExtra);
        casePreview.addClassName("case-preview");
        casePreview.add("尚未生成用例。选择功能点后点“生成预览”：AI 已配置时把该功能点的界面元素连同页面截图一起交给模型生成，未配置时按元素类型规则生成。");
        panel.add(head, options, casePreview);
        panel.expand(casePreview);
        return panel;
    }

    private void clearPendingCases() {
        pendingCases = new ArrayList<>();
        pendingDisplayCases = new ArrayList<>();
        casePreview.removeAll();
        casePreview.add("尚未生成用例。选择功能点后点“生成预览”。");
        fitCasesPanel();
    }

    private void loadProject(ProjectSummary project) {
        if (project == null) return;
        workspace = workspaceService.getProject(project.id());
        // 设置系统下拉会触发它自己的监听器；加载项目时只让下面这一次 refreshTree() 干活，
        // 否则首屏会把整棵树建两遍（1300 条用例时就是两倍的等待）。
        loadingProject = true;
        try {
            systemSelect.setItems(workspace.systems);
            if (!workspace.systems.isEmpty()) systemSelect.setValue(workspace.systems.getFirst());
        } finally {
            loadingProject = false;
        }
        refreshTree();
    }

    /**
     * 地址栏回填：换系统/环境时填回这套环境上次访问的页面，没有就填环境地址。
     * 同一环境下不动地址栏，免得把用户刚输入一半的地址覆盖掉。
     */
    private void applyCachedAddress() {
        if (workspace == null) return;
        SystemUnderTest system = systemSelect.getValue();
        TestEnvironment environment = environmentSelect.getValue();
        if (system == null || environment == null) return;
        String key = system.getId() + "|" + environment.getId();
        if (key.equals(addressContextKey)) return;
        addressContextKey = key;
        String cached = workspaceService.lastBrowserUrl(workspace.projectId, system.getId(), environment.getId());
        String value = cached == null || cached.isBlank() ? environment.getBaseUrl() : cached;
        address.setValue(value == null ? "" : value);
    }

    private void refreshTree() {
        if (workspace == null || systemSelect.getValue() == null) {
            roots.clear(); children.clear();
            tree.render(List.of(), Map.of());
            treeCaseCount.setText("0 用例");
            treeStats.setText("系统 0 · 模块 0 · 一级标题 0 · 二级标题 0 · 三级标题 0 · 功能点 0 · 用例 0");
            genScope.setText("范围：0 个功能点");
            return;
        }
        String systemId = systemSelect.getValue().getId();
        List<TestEnvironment> environments = workspace.environments.stream()
                .filter(e -> e.getSystemId().equals(systemId)).toList();
        environmentSelect.setItems(environments);
        TestEnvironment selectedEnvironment = environments.stream()
                .filter(TestEnvironment::isDefaultEnvironment).findFirst()
                .orElse(environments.isEmpty() ? null : environments.getFirst());
        environmentSelect.setValue(selectedEnvironment);
        applyCachedAddress();
        roots.clear(); children.clear();
        Map<String, TreeNode> valid = new HashMap<>();
        workspace.nodes.stream().filter(n -> systemId.equals(n.getSystemId()) && n.getDeletedAt() == null).forEach(n -> valid.put(n.getId(), n));
        for (TreeNode node : valid.values()) {
            if (node.getParentId() == null || !valid.containsKey(node.getParentId())) roots.add(node);
            else children.computeIfAbsent(node.getParentId(), ignored -> new ArrayList<>()).add(node);
        }
        List<TestCase> systemCases = workspace.testCases.stream()
                .filter(c -> systemId.equals(c.getSystemId()) && c.getDeletedAt() == null).toList();
        TreeNode unbound = null;
        for (TestCase testCase : systemCases) {
            boolean bound = testCase.getPointId() != null && valid.containsKey(testCase.getPointId());
            TreeNode parent;
            if (bound) parent = valid.get(testCase.getPointId());
            else {
                if (unbound == null) {
                    unbound = new TreeNode(workspace.projectId, systemId, null, NodeType.MODULE, "未绑定用例");
                    unbound.setId("unbound");
                    roots.add(unbound);
                }
                parent = unbound;
            }
            TreeNode leaf = new TreeNode(workspace.projectId, systemId, parent.getId(), NodeType.POINT,
                    testCase.getTitle());
            leaf.setId("case:" + testCase.getId());
            children.computeIfAbsent(parent.getId(), ignored -> new ArrayList<>()).add(leaf);
        }
        // 首屏只铺到「一级目录」：1300+ 用例一次性全展开，服务端要建上万个 DOM 节点、浏览器再排一遍，
        // 打开首页会明显卡。小项目（< 400 条用例）维持全展开；每个系统只在第一次打开时自动收起，
        // 之后按用户手动展开/收起的状态走。
        if (systemCases.size() >= 400 && autoCollapsedSystems.add(systemId)) {
            for (TreeNode node : valid.values()) {
                String id = node.getId();
                if (id == null) continue;
                if (node.getNodeType() == NodeType.MODULE || node.getNodeType() == NodeType.TITLE1) continue;
                if (children.getOrDefault(id, List.of()).isEmpty()) continue;
                collapsedTreeIds.add(id);
            }
        }
        renderTree();
        treeCaseCount.setText(systemCases.size() + " 用例");
        updateTreeStats(valid.values().stream().toList(), systemCases.size());
        // 只有切到另一个系统时才重建元素列表：发现结果只存在内存里，
        // 若每次刷新树都从工作区重新读，会把未入库的发现结果替换成“只剩已标记项”。
        if (!systemId.equals(elementSystemId)) {
            elementSystemId = systemId;
            discoveredElements = new ArrayList<>(workspace.elements.stream()
                    .filter(e -> systemId.equals(e.getSystemId()) && !e.isArchived() && e.getDeletedAt() == null)
                    .toList());
            selectedElementIds.clear();
        }
        syncElementMarks(systemId);
        applyElementFilter();
        updateGenScope();
    }

    /** 树上方的统计条：当前系统的模块/各级标题/功能点/用例总数。 */
    private void updateTreeStats(List<TreeNode> nodes, int caseCount) {
        long modules = countType(nodes, NodeType.MODULE);
        long l1 = countType(nodes, NodeType.TITLE1);
        long l2 = countType(nodes, NodeType.TITLE2);
        long l3 = countType(nodes, NodeType.TITLE3);
        long points = countType(nodes, NodeType.POINT);
        int systems = workspace == null || workspace.systems == null ? 0 : workspace.systems.size();
        treeStats.setText("系统 " + systems + " · 模块 " + modules + " · 一级标题 " + l1
                + " · 二级标题 " + l2 + " · 三级标题 " + l3 + " · 功能点 " + points + " · 用例 " + caseCount);
    }

    private long countType(List<TreeNode> nodes, NodeType type) {
        return nodes.stream().filter(n -> n.getNodeType() == type).count();
    }

    /** 用工作区里的真实标记状态刷新内存元素，保证镜像与列表能区分“已标记/未标记”。 */
    private void syncElementMarks(String systemId) {
        if (workspace == null) return;
        Map<String, ElementCandidate> stored = new HashMap<>();
        for (ElementCandidate element : workspace.elements) {
            if (systemId.equals(element.getSystemId()) && !element.isArchived() && element.getDeletedAt() == null) {
                stored.put(element.getId(), element);
            }
        }
        for (ElementCandidate element : discoveredElements) {
            ElementCandidate saved = stored.get(element.getId());
            element.setConfirmed(saved != null && saved.isConfirmed());
        }
    }

    private record TreeView(List<FunctionTree.Row> roots, Map<String, List<FunctionTree.Row>> children) {}

    private void renderTree() {
        recomputeChecks();
        TreeView view = buildTreeView(Set.of(), null, true, true);
        tree.render(view.roots(), view.children());
    }

    /**
     * 树视图：顶层永远是 Root “/”，真实顶层节点挂在其下。
     * excluded 用于“移动到”时排除被移动节点及其子孙；pickedId 用于高亮目标；
     * showChecks=false 时（选择上级用的弹框树）勾选框不跟随主树的勾选状态。
     */
    private TreeView buildTreeView(Set<String> excluded, String pickedId, boolean includeCases,
                                   boolean showChecks) {
        Map<String, List<FunctionTree.Row>> out = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        int total = countDescendants(roots, counts);
        List<FunctionTree.Row> top = new ArrayList<>();
        for (TreeNode node : sortedNodes(roots)) {
            if (excluded.contains(node.getId())) continue;
            if (!includeCases && node.getId() != null && node.getId().startsWith("case:")) continue;
            top.add(row(node, excluded, pickedId, out, includeCases, showChecks, counts));
        }
        List<FunctionTree.Row> rootRows = new ArrayList<>();
        rootRows.add(new FunctionTree.Row("ft-root", "/", "Root", "root",
                "顶层根节点：勾选它等于勾选整棵树", !top.isEmpty(),
                !collapsedTreeIds.contains("ft-root"),
                showChecks && rootAllChecked,
                "ft-root".equals(pickedId) || (showChecks && checkMode && rootAllChecked),
                showChecks && rootHalfChecked,
                true, total));
        out.put("ft-root", top);
        return new TreeView(rootRows, out);
    }

    /** 统计每个节点下的用例数（递归），用于树上的数字标记。 */
    private int countDescendants(List<TreeNode> level, Map<String, Integer> counts) {
        return countCases(children, level, counts);
    }

    /**
     * 树上的数字标记 = 该节点下**用例**的条数（含全部子孙）。
     * 这样每一级都能对上：父节点的数 = 各子节点的数之和 + 自己直接挂的用例数，
     * 根的数和顶部「用例 N」一致；没有用例的节点不显示数字。
     * （早先数的是「子节点 + 用例」的混合条目，父节点 9 而子节点 4 + 3，看着就是错的。）
     */
    static int countCases(Map<String, List<TreeNode>> children, List<TreeNode> level, Map<String, Integer> counts) {
        int total = 0;
        for (TreeNode node : level) {
            if (node.getId() == null) continue;
            int below = countCases(children, children.getOrDefault(node.getId(), List.of()), counts);
            counts.put(node.getId(), below);
            total += below + (node.getId().startsWith("case:") ? 1 : 0);
        }
        return total;
    }

    private FunctionTree.Row row(TreeNode node, Set<String> excluded, String pickedId,
                                 Map<String, List<FunctionTree.Row>> out, boolean includeCases,
                                 boolean showChecks, Map<String, Integer> counts) {
        String id = node.getId();
        List<FunctionTree.Row> kids = new ArrayList<>();
        for (TreeNode kid : sortedNodes(children.getOrDefault(id, List.of()))) {
            if (excluded.contains(kid.getId())) continue;
            if (!includeCases && kid.getId() != null && kid.getId().startsWith("case:")) continue;
            kids.add(row(kid, excluded, pickedId, out, includeCases, showChecks, counts));
        }
        if (id != null) out.put(id, kids);
        boolean caseNode = id != null && id.startsWith("case:");
        boolean unbound = "unbound".equals(id);
        String badge = unbound ? "未绑定" : caseNode ? "用例" : typeName(node.getNodeType());
        String kind = caseNode ? "case" : node.getNodeType().name().toLowerCase();
        String title = node.getName() + " · " + badge;
        boolean checked = showChecks && id != null && checkedTreeIds.contains(id);
        boolean half = showChecks && id != null && indeterminateTreeIds.contains(id);
        // 高亮跟着"当前生效的选法"走：勾选模式下显示勾选集，点选模式下显示点选集，
        // 否则 Ctrl 点选之后看不出选了什么（行还是勾选框那批高亮的）。
        boolean active = id != null && id.equals(pickedId);
        if (showChecks && !active) {
            active = checkMode ? checked : (id != null && selectedTreeIds.contains(id));
        }
        return new FunctionTree.Row(id, node.getName(), badge, kind, title,
                !kids.isEmpty(), !collapsedTreeIds.contains(id),
                checked, active, half,
                caseNode || unbound,
                caseNode || unbound ? 0 : counts.getOrDefault(id, 0));
    }

    /** 同级排序：手动排序值优先；没排过的（0）按创建时间先后，再按名称兜底。 */
    private static List<TreeNode> sortedNodes(List<TreeNode> source) {
        List<TreeNode> copy = new ArrayList<>(source);
        copy.sort(WorkspaceView::compareSiblings);
        return copy;
    }

    private static int compareSiblings(TreeNode a, TreeNode b) {
        int byOrder = Integer.compare(a.getSortOrder(), b.getSortOrder());
        if (byOrder != 0) return byOrder;
        long left = a.getCreatedAt() == null ? Long.MAX_VALUE : a.getCreatedAt().toEpochMilli();
        long right = b.getCreatedAt() == null ? Long.MAX_VALUE : b.getCreatedAt().toEpochMilli();
        int byCreated = Long.compare(left, right);
        if (byCreated != 0) return byCreated;
        int byId = String.valueOf(a.getId()).compareTo(String.valueOf(b.getId()));
        return byId != 0 ? byId : nullSafe(a.getName()).compareTo(nullSafe(b.getName()));
    }

    /** 拖放到行上 30% = 排到它前面，下 30% = 排到它后面，中间 = 挂到它下。 */
    private void treeDrop(String draggedId, String targetId, String zone) {
        if (workspace == null || draggedId == null || targetId == null) return;
        if ("into".equals(zone) || "ft-root".equals(targetId)) {
            treeMove(draggedId, targetId);
            return;
        }
        if (draggedId.startsWith("case:") || targetId.startsWith("case:")
                || "unbound".equals(draggedId) || "unbound".equals(targetId)) return;
        TreeNode dragged = findNode(draggedId);
        TreeNode target = findNode(targetId);
        if (dragged == null || target == null || dragged.getId().equals(target.getId())) return;
        String parentId = target.getParentId();
        if (parentId != null && (parentId.equals(draggedId) || isDescendant(draggedId, parentId))) {
            Notification.show("不能移动到自身或其子孙节点下");
            return;
        }
        if (parentId != null) {
            TreeNode newParent = findNode(parentId);
            if (newParent != null && newParent.getNodeType() == NodeType.POINT) {
                Notification.show("「" + newParent.getName() + "」是功能点：功能点下只能挂用例，不能再放节点");
                return;
            }
        }
        dragged.setParentId(parentId);
        List<TreeNode> siblings = new ArrayList<>();
        for (TreeNode node : workspace.nodes) {
            if (node.getDeletedAt() != null || node.getId().equals(draggedId)) continue;
            if (java.util.Objects.equals(node.getParentId(), parentId)) siblings.add(node);
        }
        siblings = sortedNodes(siblings);
        int index = siblings.size();
        for (int i = 0; i < siblings.size(); i++) {
            if (target.getId().equals(siblings.get(i).getId())) { index = i; break; }
        }
        if ("after".equals(zone)) index++;
        siblings.add(Math.min(index, siblings.size()), dragged);
        for (int i = 0; i < siblings.size(); i++) siblings.get(i).setSortOrder(i + 1);
        workspace = workspaceService.save(workspace);
        refreshTree();
        saved("已调整「" + dragged.getName() + "」的排序");
    }

    /** 新节点默认排到同级最后，保持“按创建先后排序”。 */
    private int nextSortOrder(String parentId) {
        return nextSortOrder(workspace == null ? List.of() : workspace.nodes, parentId);
    }

    private static int nextSortOrder(List<TreeNode> pool, String parentId) {
        int max = 0;
        for (TreeNode node : pool) {
            if (node.getDeletedAt() != null) continue;
            if (!java.util.Objects.equals(node.getParentId(), parentId)) continue;
            max = Math.max(max, node.getSortOrder());
        }
        return max + 1;
    }

    private void treeToggle(String id) {
        if (id == null) return;
        if (!collapsedTreeIds.remove(id)) collapsedTreeIds.add(id);
        renderTree();
    }

    /**
     * 结构整理：把历史上「功能点挂在功能点下」的节点整体修一遍。
     * 每行给出建议的新父级（最近的、不是功能点的祖先），可逐行改，确认后一次保存。
     */
    private void openTreeRepairDialog() {
        if (systemSelect.getValue() == null) { Notification.show("请先选择被测系统"); return; }
        Map<String, TreeNode> byId = nodesById();
        List<TreeNode> violations = byId.values().stream()
                .filter(n -> n.getParentId() != null)
                .filter(n -> {
                    TreeNode parent = byId.get(n.getParentId());
                    return parent != null && parent.getNodeType() == NodeType.POINT;
                })
                .toList();
        if (violations.isEmpty()) {
            Notification.show("树结构正常：没有「功能点挂在功能点下」的节点");
            return;
        }
        List<TreeNode> candidates = orderedNodeChoices(null).stream()
                .filter(n -> n.getNodeType() != NodeType.POINT)
                .toList();
        Dialog dialog = dialog("结构整理（" + violations.size() + " 个节点需要调整）");
        dialog.setWidth("720px");
        VerticalLayout rows = new VerticalLayout();
        rows.setPadding(false);
        rows.setSpacing(false);
        rows.addClassName("repair-rows");
        Map<String, ComboBox<TreeNode>> controls = new LinkedHashMap<>();
        for (TreeNode node : violations) {
            HorizontalLayout row = new HorizontalLayout();
            row.addClassName("repair-row");
            row.setWidthFull();
            row.setAlignItems(Alignment.CENTER);
            Span label = new Span(node.getName() + "（" + typeName(node.getNodeType()) + "）");
            label.addClassName("repair-name");
            TreeNode currentParent = node.getParentId() == null ? null : byId.get(node.getParentId());
            String currentText = currentParent == null ? "顶层" : currentParent.getName();
            ComboBox<TreeNode> target = new ComboBox<>();
            target.setWidthFull();
            target.setItems(candidates.stream()
                    .filter(n -> !n.getId().equals(node.getId()) && !isDescendant(node.getId(), n.getId()))
                    .toList());
            target.setItemLabelGenerator(n -> parentLabel(n, byId) + " · " + typeName(n.getNodeType()));
            target.setClearButtonVisible(true);
            TreeNode suggested = nearestNonPointAncestor(node, byId);
            target.setValue(suggested);
            target.setPlaceholder("不选 = 移到顶层");
            controls.put(node.getId(), target);
            row.add(label);
            Span arrow = new Span("现父级：" + currentText + " →");
            arrow.addClassName("repair-arrow");
            row.add(arrow);
            row.expand(target);
            row.add(target);
            rows.add(row);
        }
        Span tip = new Span("建议值已按「最近的、不是功能点的祖先」填好，可直接确认；也可以逐行改到别的模块/标题下。功能点本身与用例不会被删除。");
        tip.addClassName("muted");
        dialog.add(formBody(tip, rows));
        footer(dialog, "按建议整理", () -> {
            int moved = 0;
            for (TreeNode node : violations) {
                ComboBox<TreeNode> target = controls.get(node.getId());
                if (target == null) continue;
                TreeNode value = target.getValue();
                String newParentId = value == null ? null : value.getId();
                if (java.util.Objects.equals(newParentId, node.getParentId())) continue;
                node.setParentId(newParentId);
                node.setSortOrder(nextSortOrder(newParentId));
                moved++;
            }
            workspace = workspaceService.save(workspace);
            dialog.close();
            refreshTree();
            saved("结构整理完成：调整了 " + moved + " 个节点");
        });
        dialog.open();
    }

    /** 沿父链往上找最近的、不是功能点的祖先；找不到就返回 null（顶层）。 */
    private TreeNode nearestNonPointAncestor(TreeNode node, Map<String, TreeNode> byId) {
        String parentId = node.getParentId();
        int guard = 0;
        while (parentId != null && guard++ < 30) {
            TreeNode parent = byId.get(parentId);
            if (parent == null) return null;
            if (parent.getNodeType() != NodeType.POINT) return parent;
            parentId = parent.getParentId();
        }
        return null;
    }

    // ───────────────────────── 右键菜单：单个节点/用例的直接操作 ─────────────────────────

    private record MenuItem(String action, String label, boolean off, boolean danger, boolean line) {
        static MenuItem of(String action, String label) { return new MenuItem(action, label, false, false, false); }
        static MenuItem off(String action, String label) { return new MenuItem(action, label, true, false, false); }
        static MenuItem danger(String action, String label) { return new MenuItem(action, label, false, true, false); }
        static MenuItem sep() { return new MenuItem(null, null, false, false, true); }
    }

    private String menuJson(List<MenuItem> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            MenuItem it = items.get(i);
            if (i > 0) sb.append(',');
            if (it.line()) { sb.append("{\"sep\":true}"); continue; }
            sb.append("{\"action\":\"").append(it.action()).append("\",\"label\":\"").append(it.label()).append('"');
            if (it.off()) sb.append(",\"disabled\":true");
            if (it.danger()) sb.append(",\"danger\":true");
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    /** 右键菜单内容按行类型区分：节点、用例行、Root 各自一组动作。 */
    private void openTreeMenu(String id, double x, double y) {
        if (id == null || workspace == null) return;
        List<MenuItem> items = new ArrayList<>();
        if (id.startsWith("case:")) {
            items.add(MenuItem.of("caseOpen", "打开用例详情"));
            items.add(MenuItem.of("caseCopy", "复制用例"));
            items.add(MenuItem.of("caseMove", "移动到其他功能点…"));
            items.add(MenuItem.of("caseType", "改优先级…"));
            items.add(MenuItem.sep());
            items.add(MenuItem.danger("caseDelete", "删除用例"));
        } else if ("ft-root".equals(id)) {
            items.add(MenuItem.of("add", "新增顶层节点"));
            items.add(MenuItem.sep());
            items.add(MenuItem.of("fold", collapsed ? "全部展开" : "全部折叠"));
        } else {
            TreeNode node = findNode(id);
            if (node == null) return;
            boolean point = node.getNodeType() == NodeType.POINT;
            items.add(MenuItem.of("open", "打开 / 查看详情"));
            items.add(point
                    ? MenuItem.off("add", "功能点下只能挂用例")
                    : MenuItem.of("add", "在此新增子节点"));
            items.add(MenuItem.sep());
            items.add(MenuItem.of("rename", "重命名"));
            items.add(MenuItem.of("copy", point ? "复制功能点（含用例）" : "复制节点（含子节点）"));
            items.add(MenuItem.of("type", "修改类型…"));
            items.add(MenuItem.of("move", "移动到…"));
            items.add(MenuItem.sep());
            items.add(MenuItem.danger("delete", "删除节点"));
        }
        tree.showMenu(id, x, y, menuJson(items));
    }

    private void treeMenuPick(String id, String action) {
        if (id == null || action == null || workspace == null) return;
        if (id.startsWith("case:")) {
            String caseId = id.substring("case:".length());
            TestCase testCase = workspace.testCases.stream()
                    .filter(c -> caseId.equals(c.getId()) && c.getDeletedAt() == null)
                    .findFirst().orElse(null);
            if (testCase == null) { Notification.show("用例已不存在，请刷新"); return; }
            switch (action) {
                case "caseOpen" -> openCaseEditor(testCase);
                case "caseCopy" -> copyCase(testCase);
                case "caseMove" -> openCaseMoveDialog(testCase);
                case "caseType" -> openCasePriorityDialog(testCase);
                case "caseDelete" -> deleteCaseFromTree(testCase);
                default -> { }
            }
            return;
        }
        if ("ft-root".equals(id)) {
            if ("add".equals(action)) openNodeDialog(null, null);
            else if ("fold".equals(action)) toggleTree();
            return;
        }
        TreeNode node = findNode(id);
        if (node == null) { Notification.show("节点已不存在，请刷新"); return; }
        switch (action) {
            case "open" -> showNode(node);
            case "add" -> openNodeDialog(null, node);
            case "rename" -> openRenameNodeDialog(node);
            case "copy" -> copyNode(node);
            case "type" -> openNodeDialog(node, null);
            case "move" -> {
                selectedTreeIds.clear();
                checkedTreeIds.clear();
                selectedTreeIds.add(node.getId());
                renderTree();
                openMoveDialog();
            }
            case "delete" -> deleteSingleNode(node);
            default -> { }
        }
    }

    private void openRenameNodeDialog(TreeNode node) {
        Dialog dialog = dialog("重命名节点");
        dialog.setWidth("420px");
        TextField name = new TextField("名称");
        name.setValue(node.getName() == null ? "" : node.getName());
        name.setWidthFull();
        Span tip = new Span("只改名称，不动层级，也不影响已绑定的用例。");
        tip.addClassName("muted");
        dialog.add(formBody(tip, name));
        footer(dialog, "保存", () -> {
            String value = name.getValue() == null ? "" : name.getValue().trim();
            if (value.isBlank()) throw new StorageException("名称不能为空");
            boolean duplicate = workspace.nodes.stream()
                    .filter(n -> n.getDeletedAt() == null && !n.getId().equals(node.getId()))
                    .filter(n -> java.util.Objects.equals(n.getParentId(), node.getParentId()))
                    .anyMatch(n -> value.equals(n.getName()));
            if (duplicate) throw new StorageException("同级下已存在同名节点");
            node.setName(value);
            workspace = workspaceService.save(workspace);
            dialog.close();
            refreshTree();
            saved("已重命名为「" + value + "」");
        });
        dialog.open();
    }

    /** 单个节点删除：叶子节点直接删；功能点下只有用例时连用例一起软删除；还有子节点的先要求移走。 */
    private void deleteSingleNode(TreeNode node) {
        boolean hasChildren = workspace.nodes.stream()
                .anyMatch(n -> node.getId().equals(n.getParentId()) && n.getDeletedAt() == null);
        List<TestCase> boundCases = workspace.testCases.stream()
                .filter(c -> node.getId().equals(c.getPointId()) && c.getDeletedAt() == null)
                .toList();
        if (hasChildren) {
            Notification.show("「" + node.getName() + "」下还有子节点：先右键把它们移走或删除，再删这个节点");
            return;
        }
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("删除节点");
        confirm.setText(boundCases.isEmpty()
                ? "确定删除「" + node.getName() + "」？删除后不可直接恢复。"
                : "「" + node.getName() + "」下还有 " + boundCases.size() + " 条用例，删除节点会一并删除这些用例（软删除，库中仍保留记录）。");
        confirm.setCancelable(true);
        confirm.setConfirmText("确认删除");
        confirm.setConfirmButtonTheme("error primary");
        confirm.addConfirmListener(e -> {
            java.time.Instant now = java.time.Instant.now();
            node.setDeletedAt(now);
            for (TestCase testCase : boundCases) testCase.setDeletedAt(now);
            workspace = workspaceService.save(workspace);
            selectedTreeIds.remove(node.getId());
            checkedTreeIds.remove(node.getId());
            explicitlyUnchecked.remove(node.getId());
            refreshTree();
            saved(boundCases.isEmpty()
                    ? "已删除「" + node.getName() + "」"
                    : "已删除「" + node.getName() + "」及 " + boundCases.size() + " 条用例");
        });
        confirm.open();
    }

    /**
     * 复制节点：连子节点、以及功能点下挂的用例一起复制，复制体挂在原节点的同级、排在后面。
     * 名称加「（副本）」避免同级重名；用例重新编号、状态回到 DRAFT（副本还没跑过）。
     */
    private void copyNode(TreeNode source) {
        List<TreeNode> subtree = subtreeOf(source);
        Map<String, String> newIdByOld = new HashMap<>();
        int order = nextSortOrder(source.getParentId());
        int seq = nextCaseSequence();
        int copiedNodes = 0;
        int copiedCases = 0;
        String copyName = uniqueSiblingName(source.getParentId(), nullSafe(source.getName()) + "（副本）");
        for (TreeNode node : subtree) {
            String parentId = node == source ? source.getParentId() : newIdByOld.get(node.getParentId());
            if (node != source && parentId == null) continue;
            TreeNode copy = new TreeNode(workspace.projectId, node.getSystemId(), parentId, node.getNodeType(),
                    node == source ? copyName : node.getName());
            copy.setDescription(node.getDescription());
            copy.setElementId(node.getElementId());
            copy.setSortOrder(order++);
            copy.onCreateForStore();
            workspace.nodes.add(copy);
            newIdByOld.put(node.getId(), copy.getId());
            copiedNodes++;
            if (node.getNodeType() == NodeType.POINT) {
                for (TestCase testCase : new ArrayList<>(workspace.testCases)) {
                    if (!node.getId().equals(testCase.getPointId()) || testCase.getDeletedAt() != null) continue;
                    workspace.testCases.add(copyCaseEntity(testCase, copy.getId(), seq++));
                    copiedCases++;
                }
            }
        }
        workspace = workspaceService.save(workspace);
        refreshTree();
        saved("已复制「" + source.getName() + "」：" + copiedNodes + " 个节点"
                + (copiedCases > 0 ? "、" + copiedCases + " 条用例" : ""));
    }

    /** 复制单条用例：内容全量复制，编号重新取，状态回到草稿。 */
    private void copyCase(TestCase source) {
        TestCase copy = copyCaseEntity(source, source.getPointId(), nextCaseSequence());
        workspace.testCases.add(copy);
        workspace = workspaceService.save(workspace);
        refreshTree();
        if (selectedNode != null) renderNodeDetail(selectedNode);
        saved("已复制用例：" + copy.getCaseNo());
    }

    private int nextCaseSequence() {
        return workspace.testCases.stream()
                .filter(tc -> tc.getDeletedAt() == null)
                .mapToInt(tc -> com.yuceyang.uitest.testcase.CaseNumbers.sequence(tc.getCaseNo()))
                .max().orElse(0) + 1;
    }

    private static TestCase copyCaseEntity(TestCase source, String pointId, int sequence) {
        TestCase copy = new TestCase(source.getProjectId(), source.getSystemId(), pointId, source.getTitle());
        copy.onCreateForStore();
        copy.setCaseNo(com.yuceyang.uitest.testcase.CaseNumbers.format(sequence));
        copy.setPreconditions(source.getPreconditions());
        copy.setSteps(source.getSteps());
        copy.setExpectedResult(source.getExpectedResult());
        copy.setActualResult(null);
        copy.setModuleName(source.getModuleName());
        copy.setPriority(source.getPriority());
        copy.setCategory(source.getCategory());
        copy.setDesignRequirement(source.getDesignRequirement());
        copy.setTestTrack(source.getTestTrack());
        copy.setRemark(source.getRemark());
        copy.setParamsText(source.getParamsText());
        copy.setMethod(source.getMethod());
        copy.setUrl(source.getUrl());
        copy.setL1(source.getL1());
        copy.setL2(source.getL2());
        copy.setAnnotation(source.getAnnotation());
        copy.setScreenshotId(source.getScreenshotId());
        copy.setStatus("DRAFT");
        return copy;
    }

    /** 先序展开某个节点的整棵子树（不含已软删除的节点）。 */
    private List<TreeNode> subtreeOf(TreeNode root) {
        List<TreeNode> out = new ArrayList<>();
        java.util.ArrayDeque<TreeNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            TreeNode node = stack.pop();
            out.add(node);
            List<TreeNode> kids = workspace.nodes.stream()
                    .filter(n -> node.getId().equals(n.getParentId()) && n.getDeletedAt() == null)
                    .sorted(Comparator.comparingInt(TreeNode::getSortOrder))
                    .toList();
            for (int i = kids.size() - 1; i >= 0; i--) stack.push(kids.get(i));
        }
        return out;
    }

    /** 同级不重名：base 已存在就依次试 base 2、base 3。 */
    private String uniqueSiblingName(String parentId, String base) {
        Set<String> used = new HashSet<>();
        for (TreeNode node : workspace.nodes) {
            if (node.getDeletedAt() != null) continue;
            if (!java.util.Objects.equals(node.getParentId(), parentId)) continue;
            used.add(nullSafe(node.getName()));
        }
        String candidate = base;
        int suffix = 2;
        while (used.contains(candidate)) candidate = base + " " + suffix++;
        return candidate;
    }

    private void deleteCaseFromTree(TestCase testCase) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("删除用例");
        confirm.setConfirmText("确认删除");
        confirm.setConfirmButtonTheme("error primary");
        confirm.setCancelable(true);
        confirm.setText("确定删除「" + (testCase.getCaseNo() == null ? "" : testCase.getCaseNo() + " ") + testCase.getTitle() + "」？");
        confirm.addConfirmListener(e -> {
            testCase.setDeletedAt(java.time.Instant.now());
            workspace = workspaceService.save(workspace);
            refreshTree();
            saved("用例已删除");
        });
        confirm.open();
    }

    private void openCasePriorityDialog(TestCase testCase) {
        Dialog dialog = dialog("调整用例");
        dialog.setWidth("440px");
        ComboBox<String> priority = new ComboBox<>("优先级");
        priority.setItems("P0", "P1", "P2", "P3");
        priority.setValue(testCase.getPriority() == null ? "P2" : testCase.getPriority());
        priority.setWidthFull();
        dialog.add(formBody(priority));
        footer(dialog, "保存", () -> {
            testCase.setPriority(priority.getValue());
            workspace = workspaceService.save(workspace);
            dialog.close();
            refreshTree();
            saved("优先级已更新");
        });
        dialog.open();
    }

    /**
     * 把用例挂到另一个节点下（右键菜单用，不会改动用例内容）。
     * 目标用功能点树本体来选：真实缩进 + 引导线，层级一眼能和左边的树对上；
     * 目标不限于功能点——目录节点也能挂（历史数据里用例本来就挂在目录下）。
     */
    private void openCaseMoveDialog(TestCase testCase) {
        openCaseMoveDialog(List.of(testCase), false);
    }

    /** 生成台预览里的用例也能改挂载点：只改内存里的预览，确认入库时才写盘，不碰已入库数据。 */
    private void openPendingCaseMoveDialog() {
        if (pendingCases.isEmpty()) { Notification.show("生成台还没有用例，先点“生成预览”"); return; }
        openCaseMoveDialog(new ArrayList<>(pendingCases), true);
    }

    private void openCaseMoveDialog(List<TestCase> cases, boolean pending) {
        if (workspace == null || cases.isEmpty()) return;
        if (roots.isEmpty()) {
            Notification.show("当前系统下还没有节点，先建功能点再移动用例");
            return;
        }
        TestCase first = cases.getFirst();
        Dialog dialog = dialog(cases.size() == 1 ? "移动用例" : "改挂载点（" + cases.size() + " 条用例）");
        dialog.setWidth("620px");
        String[] picked = {first.getPointId()};
        Span current = new Span();
        current.addClassName("picked-target");
        Runnable showCurrent = () -> {
            TreeNode target = picked[0] == null ? null : findNode(picked[0]);
            String toText = target == null ? "尚未选择"
                    : target.getName() + "（" + typeName(target.getNodeType()) + "）";
            if (cases.size() == 1) refreshCaseMoveCurrent(current, first, picked[0]);
            else current.setText(cases.size() + " 条用例 → 目标：" + toText);
        };
        FunctionTree[] holder = new FunctionTree[1];
        FunctionTree picker = new FunctionTree(new FunctionTree.Handler() {
            @Override public void toggle(String id) {
                if (id == null) return;
                if (!collapsedTreeIds.remove(id)) collapsedTreeIds.add(id);
                renderCasePicker(holder[0], picked);
            }
            @Override public void select(String id, boolean ctrl, boolean shift) {
                if (id == null) return;
                if ("ft-root".equals(id)) {
                    Notification.show("用例要挂在某个节点下，不能直接挂到 Root");
                    return;
                }
                picked[0] = id;
                renderCasePicker(holder[0], picked);
                showCurrent.run();
            }
            @Override public void check(String id) { select(id, false, false); }
            @Override public void open(String id) { select(id, false, false); }
            @Override public void move(String draggedId, String targetId) { }
        });
        holder[0] = picker;
        picker.setHeight("320px");
        picker.addClassName("ft-picker");
        // 用例可以挂在任意节点（含功能点）下，灰色不可选只适用于“节点换父级”。
        picker.addClassName("ft-picker-any");
        renderCasePicker(picker, picked);
        showCurrent.run();
        Span tip = new Span(cases.size() == 1
                ? "「" + first.getTitle() + "」将挂到所选节点下；缩进和功能点树一致。用例内容与编号不变。"
                : "这 " + cases.size() + " 条用例将一起挂到所选节点下（功能点、目录都行）；用例内容与编号不变。"
                        + (pending ? "现在改的只是预览，入库时按新挂载点写入。" : ""));
        tip.addClassName("muted");
        dialog.add(formBody(tip, picker, current));
        footer(dialog, "移动到此节点", () -> {
            TreeNode node = findNode(picked[0]);
            if (node == null) throw new StorageException("请先在树里选择目标节点");
            for (TestCase testCase : cases) testCase.setPointId(node.getId());
            dialog.close();
            if (pending) {
                renderPendingCases("已更新挂载点", "");
                saved("预览里的 " + cases.size() + " 条用例已改挂到「" + node.getName() + "」");
            } else {
                workspace = workspaceService.save(workspace);
                refreshTree();
                saved(cases.size() == 1 ? "用例已移动到「" + node.getName() + "」"
                        : "已把 " + cases.size() + " 条用例改挂到「" + node.getName() + "」");
            }
        });
        dialog.open();
    }

    /** 移动用例弹框里的树：整棵系统树都在（用例行不显示），当前落点高亮。 */
    private void renderCasePicker(FunctionTree picker, String[] picked) {
        TreeView view = buildTreeView(Set.of(), picked[0], false, false);
        picker.render(view.roots(), view.children());
    }

    private void refreshCaseMoveCurrent(Span current, TestCase testCase, String targetId) {
        TreeNode from = findNode(testCase.getPointId());
        TreeNode to = findNode(targetId);
        String fromText = from == null ? "未挂载" : from.getName() + "（" + typeName(from.getNodeType()) + "）";
        String toText = to == null ? "尚未选择" : to.getName() + "（" + typeName(to.getNodeType()) + "）";
        current.setText("当前：" + fromText + " → 目标：" + toText);
        current.getElement().setAttribute("data-target", to == null ? "" : to.getId());
    }

    private void treeOpen(String id) {
        TreeNode node = findNode(id);
        if (node != null) showNode(node);
        else if (id != null && id.startsWith("case:")) {
            String caseId = id.substring("case:".length());
            workspace.testCases.stream().filter(c -> caseId.equals(c.getId())).findFirst().ifPresent(this::openCaseEditor);
        }
    }

    private void treeSelect(String id, boolean ctrl, boolean shift) {
        if (id == null || workspace == null) return;
        if ("ft-root".equals(id)) {
            if (!ctrl && !shift) {
                checkMode = false;
                selectedTreeIds.clear();
                lastSelectedTreeId = null;
                updateTreeNodeStates();
                updateGenScope();
            }
            return;
        }
        if ("unbound".equals(id)) { treeOpen(id); return; }
        // 用例行：普通点击是"看详情"，Ctrl/Shift 点击要先当作一行加入多选——
        // 树里绝大多数行是用例行，不这样批量删/移动/导出就选不到它们。
        if (id.startsWith("case:") && !ctrl && !shift) { treeOpen(id); return; }
        // 点选（含 Ctrl/Shift）就是"改用点选集"：之后批量操作不再受勾选框影响。
        checkMode = false;
        boolean ranged = false;
        if (shift && lastSelectedTreeId != null) {
            List<String> order = visibleRowIds();
            int from = order.indexOf(lastSelectedTreeId);
            int to = order.indexOf(id);
            if (from >= 0 && to >= 0) {
                if (!ctrl) selectedTreeIds.clear();
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) selectedTreeIds.add(order.get(i));
                lastSelectedTreeId = id;
                ranged = true;
            }
            // 锚点行被收起/删掉后 indexOf 会是 -1：这时按普通点选处理，不要“点了没反应”。
        }
        if (!ranged) {
            if (ctrl) {
                if (!selectedTreeIds.remove(id)) selectedTreeIds.add(id);
            } else {
                selectedTreeIds.clear();
                selectedTreeIds.add(id);
            }
            lastSelectedTreeId = id;
        }
        updateTreeNodeStates();
        updateGenScope();
        TreeNode node = findNode(id);
        if (node != null && !ctrl && !shift) showNode(node);
    }

    /**
     * 只刷新树上已有行的勾选/高亮状态，不重建 DOM。
     * 1300+ 行时每点一下都整棵重建，会让浏览器卡顿并弹顶部加载进度条；
     * 勾选和点选改变的都是行状态，交给 {@link FunctionTree#applyStates} 增量更新即可。
     */
    private void updateTreeNodeStates() {
        Set<String> checked = new HashSet<>(checkedTreeIds);
        Set<String> half = new HashSet<>(indeterminateTreeIds);
        if (rootAllChecked) checked.add("ft-root");
        if (rootHalfChecked) half.add("ft-root");
        Set<String> active = checkMode ? new HashSet<>(checkedTreeIds) : new HashSet<>(selectedTreeIds);
        if (checkMode && rootAllChecked) active.add("ft-root");
        tree.applyStates(checked, half, active);
    }

    /**
     * 勾选框是三下循环（每下语义不同）：
     * <ol>
     *   <li>空 → 勾上自己和全部子孙；</li>
     *   <li>自己已勾 → 只取消自己，子孙保持勾选（父级显示为空，不显示「-」半选）；</li>
     *   <li>自己是空的但子孙还勾着（第 2 步留下的状态）→ 连子孙一起全部取消。</li>
     * </ol>
     * 所以「父节点不选、下面全选」这一态既能显示为空，又能被删除/改类型时只作用于下面。
     * Root「/」是整棵树的开关。
     */
    private void treeCheck(String id) {
        if (id == null || workspace == null) return;
        // 勾选框一碰就切到"勾选集"，批量操作随之只看勾选。
        // 从点选切过来时清掉上一次的点选集：否则勾完再 Ctrl 点一行，高亮会跳回上一次的旧选法，
        // 看起来就像"Ctrl 选的不算数"。
        if (!checkMode) {
            selectedTreeIds.clear();
            lastSelectedTreeId = null;
        }
        checkMode = true;
        if ("ft-root".equals(id)) {
            boolean all = rootAllChecked;
            checkedTreeIds.clear();
            explicitlyUnchecked.clear();
            if (!all) collectCheckable(roots, checkedTreeIds);
            recomputeChecks();
            updateTreeNodeStates();
            updateGenScope();
            return;
        }
        if (id.startsWith("case:")) {
            // 用例行是叶子：勾/不勾两态，再由 recomputeChecks 回推所属功能点是否全选。
            if (checkedTreeIds.remove(id)) explicitlyUnchecked.add(id);
            else { checkedTreeIds.add(id); explicitlyUnchecked.remove(id); }
            recomputeChecks();
            updateTreeNodeStates();
            updateGenScope();
            return;
        }
        TreeNode node = findNode(id);
        if (node == null) return;
        Set<String> subtree = new LinkedHashSet<>();
        collectCheckable(List.of(node), subtree);
        if (checkedTreeIds.contains(id)) {
            // 第二下：只取消自己，子孙保持勾选。
            checkedTreeIds.remove(id);
            explicitlyUnchecked.add(id);
        } else if (explicitlyUnchecked.contains(id)) {
            // 第三下：把这一支的勾全部去掉。
            checkedTreeIds.removeAll(subtree);
            explicitlyUnchecked.removeAll(subtree);
            explicitlyUnchecked.remove(id);
        } else {
            // 第一下：勾上自己和全部子孙。
            checkedTreeIds.addAll(subtree);
            subtree.forEach(explicitlyUnchecked::remove);
            explicitlyUnchecked.remove(id);
        }
        recomputeChecks();
        updateTreeNodeStates();
        updateGenScope();
    }

    /**
     * 收集可勾选的节点及其全部子孙。用例行也要一起勾上：
     * 勾功能点时用户期望它下面的用例一起被选中。
     */
    private void collectCheckable(List<TreeNode> level, Set<String> out) {
        for (TreeNode node : level) {
            if (node.getId() == null || "unbound".equals(node.getId())) continue;
            out.add(node.getId());
            collectCheckable(children.getOrDefault(node.getId(), List.of()), out);
        }
    }

    /** 重算勾选状态：先剔除已不存在的节点，再从下往上回推父级。勾选框只有「勾上 / 空」两种显示，不再有「-」半选。 */
    private void recomputeChecks() {
        indeterminateTreeIds.clear();
        rootAllChecked = false;
        rootHalfChecked = false;
        if (workspace == null || roots.isEmpty()) {
            checkedTreeIds.clear();
            explicitlyUnchecked.clear();
            selectedTreeIds.clear();
            lastSelectedTreeId = null;
            return;
        }
        Set<String> existing = new LinkedHashSet<>();
        collectCheckable(roots, existing);
        checkedTreeIds.retainAll(existing);
        explicitlyUnchecked.retainAll(existing);
        // 点选集同理：删掉的用例、切走系统的节点不能留在选法里（否则批量操作会带上看不见的行）。
        selectedTreeIds.retainAll(existing);
        if (lastSelectedTreeId != null && !existing.contains(lastSelectedTreeId)) lastSelectedTreeId = null;

        boolean all = true;
        for (TreeNode node : checkableChildren(roots)) {
            all &= recomputeChecks(node);
        }
        rootAllChecked = all && !existing.isEmpty();
    }

    /**
     * 递归回推一个节点：子孙全勾 → 勾上（除非用户手动取消过这个节点）；否则为空。
     * 空着的节点即使下面还勾着也不显示半选——那正是「父节点不选、下面全选」的第三态。
     * @return 该节点自身是否处于“已勾”状态
     */
    private boolean recomputeChecks(TreeNode node) {
        String id = node.getId();
        List<TreeNode> kids = checkableChildren(children.getOrDefault(node.getId(), List.of()));
        if (!kids.isEmpty()) {
            boolean all = true;
            for (TreeNode kid : kids) {
                all &= recomputeChecks(kid);
            }
            boolean blocked = explicitlyUnchecked.contains(id);
            if (all && !blocked) {
                checkedTreeIds.add(id);
            } else {
                checkedTreeIds.remove(id);
            }
            indeterminateTreeIds.remove(id);
        }
        return checkedTreeIds.contains(id);
    }

    /** 子级里参与勾选的节点：真实节点 + 用例行，排除“未绑定”这个虚拟容器。 */
    private List<TreeNode> checkableChildren(List<TreeNode> level) {
        List<TreeNode> list = new ArrayList<>();
        for (TreeNode node : level) {
            if (node.getId() != null && !"unbound".equals(node.getId())) list.add(node);
        }
        return list;
    }

    /**
     * 操作范围：认最后一次手势。
     * 勾过勾选框就按勾选集；点选（含 Ctrl/Shift）之后按点选集；两者都空时回落到勾选集。
     */
    private Set<String> effectiveTreeIds() {
        if (checkMode && !checkedTreeIds.isEmpty()) return checkedTreeIds;
        if (!selectedTreeIds.isEmpty()) return selectedTreeIds;
        return checkedTreeIds;
    }

    private void treeMove(String draggedId, String targetId) {
        if (workspace == null || draggedId == null) return;
        TreeNode dragged = findNode(draggedId);
        TreeNode target = findNode(targetId);
        String newParentId = "ft-root".equals(targetId) ? null : (target == null ? null : target.getId());
        if (dragged == null) return;
        if (newParentId != null && (newParentId.equals(draggedId) || isDescendant(draggedId, newParentId))) {
            Notification.show("不能移动到自身或其子孙节点下");
            return;
        }
        if (target != null && target.getNodeType() == NodeType.POINT) {
            Notification.show("「" + target.getName() + "」是功能点：功能点下只能挂用例，不能再放节点");
            return;
        }
        if (java.util.Objects.equals(newParentId, dragged.getParentId())) return;
        int order = nextSortOrder(newParentId);
        dragged.setParentId(newParentId);
        dragged.setSortOrder(order);
        workspace = workspaceService.save(workspace);
        refreshTree();
        saved("已把「" + dragged.getName() + "」移动到 " + (newParentId == null ? "Root /" : target.getName()));
    }

    private boolean isDescendant(String ancestorId, String nodeId) {
        TreeNode current = findNode(nodeId);
        int guard = 0;
        while (current != null && guard++ < 30) {
            if (ancestorId.equals(current.getParentId())) return true;
            current = findNode(current.getParentId());
        }
        return false;
    }

    private TreeNode findNode(String id) {
        if (id == null || workspace == null) return null;
        return workspace.nodes.stream()
                .filter(n -> id.equals(n.getId()) && n.getDeletedAt() == null)
                .findFirst().orElse(null);
    }

    /**
     * Shift 连选的范围：树里**看得见的每一行**，用例行也算一行。
     * 只用节点会漏掉夹在中间的用例，选出来的区间和肉眼看到的不一样。
     */
    private List<String> visibleRowIds() {
        List<String> order = new ArrayList<>();
        collectVisibleRows(roots, order);
        return order;
    }

    private void collectVisibleRows(List<TreeNode> level, List<String> order) {
        for (TreeNode node : sortedNodes(level)) {
            String id = node.getId();
            if (id == null || "unbound".equals(id)) continue;
            order.add(id);
            if (id.startsWith("case:")) continue;
            if (!collapsedTreeIds.contains(id)) collectVisibleRows(children.getOrDefault(id, List.of()), order);
        }
    }

    private void updateGenScope() {
        List<String> ids = generationPointIds();
        Set<String> chosen = effectiveTreeIds();
        long cases = chosen.stream().filter(id -> id != null && id.startsWith("case:")).count();
        String scopeName = chosen.isEmpty()
                ? (selectedNode != null && selectedNode.getNodeType() == NodeType.POINT ? "当前功能点" : "全部")
                : cases == chosen.size() ? "选中 " + cases + " 条用例，只影响批量操作"
                : (chosen == checkedTreeIds ? "勾选节点" : "选中节点");
        genScope.setText("范围：" + ids.size() + " 个功能点（" + scopeName + "）");
        updateSelectionHint();
    }

    /** 树上方的「已选 N 个节点 + M 条用例」：Ctrl/Shift 选到的东西得能看见，否则会以为没选上。 */
    private void updateSelectionHint() {
        int nodes = selectedTreeNodes().size();
        int cases = selectedTreeCases().size();
        if (nodes == 0 && cases == 0) {
            treeSelection.setText("");
            treeSelection.setVisible(false);
            return;
        }
        treeSelection.setText("已选 " + (nodes > 0 ? nodes + " 个节点" : "")
                + (nodes > 0 && cases > 0 ? " + " : "")
                + (cases > 0 ? cases + " 条用例" : ""));
        treeSelection.setVisible(true);
    }

    private List<String> generationPointIds() {
        if (workspace == null) return List.of();
        String systemId = systemSelect.getValue() == null ? null : systemSelect.getValue().getId();
        List<TreeNode> points = workspace.nodes.stream()
                .filter(n -> n.getDeletedAt() == null && n.getNodeType() == NodeType.POINT)
                .filter(n -> systemId == null || systemId.equals(n.getSystemId()))
                .toList();
        Set<String> scope = new HashSet<>();
        Set<String> chosen = effectiveTreeIds();
        if (!chosen.isEmpty()) {
            for (String id : chosen) collectPointIds(id, scope);
            return points.stream().filter(p -> scope.contains(p.getId())).map(TreeNode::getId).toList();
        }
        if (selectedNode != null && selectedNode.getNodeType() == NodeType.POINT
                && points.stream().anyMatch(p -> p.getId().equals(selectedNode.getId()))) {
            return List.of(selectedNode.getId());
        }
        return points.stream().map(TreeNode::getId).toList();
    }

    private void collectPointIds(String nodeId, Set<String> out) {
        TreeNode node = findNode(nodeId);
        if (node == null) return;
        if (node.getNodeType() == NodeType.POINT) { out.add(node.getId()); return; }
        for (TreeNode child : children.getOrDefault(nodeId, List.of())) collectPointIds(child.getId(), out);
    }

    private void batchChangeType() {
        List<TreeNode> chosen = selectedTreeNodes();
        int caseCount = selectedTreeCases().size();
        if (chosen.isEmpty()) {
            Notification.show(caseCount > 0
                    ? "选中的 " + caseCount + " 条都是用例：用例没有类型，可以移动或删除"
                    : "请先勾选或点选要修改的树节点");
            return;
        }
        Dialog dialog = dialog("批量修改类型");
        dialog.setWidth("420px");
        ComboBox<NodeType> type = new ComboBox<>("新类型");
        type.setItems(NodeType.values());
        type.setItemLabelGenerator(this::typeName);
        type.setValue(NodeType.POINT);
        Span rule = new Span("改成功能点时会跳过「下面还有子节点」的节点（功能点下只能挂用例）。");
        rule.addClassName("muted");
        dialog.add(formBody(new Span("将修改 " + chosen.size() + " 个节点（不含用例节点）"
                + (caseCount > 0 ? "；选中的 " + caseCount + " 条用例会跳过" : "")), type, rule));
        footer(dialog, "确认修改", () -> {
            NodeType target = type.getValue();
            int changed = 0;
            int skipped = 0;
            for (TreeNode node : chosen) {
                if (target == NodeType.POINT) {
                    boolean hasKids = workspace.nodes.stream()
                            .anyMatch(n -> n.getDeletedAt() == null && node.getId().equals(n.getParentId()));
                    TreeNode parent = node.getParentId() == null ? null : findNode(node.getParentId());
                    if (hasKids || (parent != null && parent.getNodeType() == NodeType.POINT)) { skipped++; continue; }
                }
                node.setNodeType(target);
                changed++;
            }
            workspace = workspaceService.save(workspace);
            dialog.close(); refreshTree();
            saved("已修改 " + changed + " 个节点类型" + (skipped > 0 ? "，跳过 " + skipped + " 个（有子节点或父级是功能点）" : ""));
        });
        dialog.open();
    }

    /**
     * 删除当前选法：树节点 + 用例行都算（勾选框勾的、Ctrl/Shift 点选的）。
     * 这样「父节点不选、下面全选」时点删除，正好只删下面那些用例，父节点保留。
     */
    private void batchDeleteNodes() {
        List<TreeNode> chosen = selectedTreeNodes();
        List<TestCase> chosenCases = selectedTreeCases();
        if (chosen.isEmpty() && chosenCases.isEmpty()) {
            Notification.show("请先勾选或点选要删除的树节点/用例");
            return;
        }
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("批量删除");
        dialog.setText("将删除选中的 " + chosen.size() + " 个节点、" + chosenCases.size()
                + " 条用例。含子节点或已绑定用例的节点会被跳过，不会丢失用例；删除后不可直接恢复。");
        dialog.setCancelable(true);
        dialog.setConfirmText("确认删除");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(event -> {
            java.time.Instant now = java.time.Instant.now();
            int deleted = 0, skipped = 0;
            for (TreeNode node : chosen) {
                boolean hasChildren = workspace.nodes.stream()
                        .anyMatch(n -> node.getId().equals(n.getParentId()) && n.getDeletedAt() == null);
                boolean hasCases = workspace.testCases.stream()
                        .anyMatch(c -> node.getId().equals(c.getPointId()) && c.getDeletedAt() == null);
                if (hasChildren || hasCases) { skipped++; continue; }
                node.setDeletedAt(now);
                deleted++;
            }
            for (TestCase testCase : chosenCases) testCase.setDeletedAt(now);
            workspace = workspaceService.save(workspace);
            selectedTreeIds.clear(); checkedTreeIds.clear(); explicitlyUnchecked.clear(); refreshTree();
            saved("已删除 " + deleted + " 个节点"
                    + (chosenCases.isEmpty() ? "" : "、" + chosenCases.size() + " 条用例")
                    + (skipped > 0 ? "，跳过 " + skipped + " 个有子节点或用例的节点" : ""));
        });
        dialog.open();
    }

    private void openMoveDialog() {
        List<TreeNode> chosen = selectedTreeNodes();
        List<TestCase> chosenCases = selectedTreeCases();
        if (chosen.isEmpty() && chosenCases.isEmpty()) {
            Notification.show("请先点选要移动的节点或用例（Ctrl 多选、Shift 连选，或直接拖拽）");
            return;
        }
        Set<String> excluded = new HashSet<>();
        for (TreeNode node : chosen) {
            excluded.add(node.getId());
            collectDescendantIds(node.getId(), excluded);
        }
        String title = chosen.isEmpty() ? "移动 " + chosenCases.size() + " 条用例"
                : chosenCases.isEmpty() ? "移动 " + chosen.size() + " 个节点"
                : "移动 " + chosen.size() + " 个节点 / " + chosenCases.size() + " 条用例";
        Dialog dialog = dialog(title);
        dialog.setWidth("560px");
        String[] picked = {null};
        FunctionTree[] holder = new FunctionTree[1];
        FunctionTree picker = new FunctionTree(new FunctionTree.Handler() {
            @Override public void toggle(String id) {
                if (id == null) return;
                if (!collapsedTreeIds.remove(id)) collapsedTreeIds.add(id);
                renderPicker(holder[0], excluded, picked);
            }
            @Override public void select(String id, boolean ctrl, boolean shift) {
                TreeNode target = id == null ? null : findNode(id);
                if (target != null && target.getNodeType() == NodeType.POINT && !chosen.isEmpty()) {
                    Notification.show("「" + target.getName() + "」是功能点：节点不能挂在功能点下（只选用例时可以）");
                    return;
                }
                picked[0] = id;
                renderPicker(holder[0], excluded, picked);
            }
            @Override public void check(String id) { select(id, false, false); }
            @Override public void open(String id) { select(id, false, false); }
            @Override public void move(String draggedId, String targetId) { }
        });
        holder[0] = picker;
        picker.setHeight("300px");
        picker.addClassName("ft-picker");
        // 只移用例时功能点就是合法目标：别把它灰成不可选（灰着却点得动最误导人）。
        if (chosen.isEmpty()) picker.addClassName("ft-picker-any");
        renderPicker(picker, excluded, picked);
        Span tip = new Span(chosen.isEmpty()
                ? "点击目标节点，选中的用例会挂到它下面（用例内容与编号不变）；不能挂到 Root。"
                : "点击目标节点作为新上级；选 Root「/」表示移到顶层。功能点（灰色）不能作为父级。被移动节点及其子孙已从可选列表排除。");
        tip.addClassName("muted");
        dialog.add(formBody(tip, picker));
        footer(dialog, "移到此处", () -> {
            if (picked[0] == null) { Notification.show("请先选择目标节点（Root / 表示顶层）"); return; }
            String targetId = picked[0];
            String newParentId = "ft-root".equals(targetId) ? null : targetId;
            TreeNode target = findNode(targetId);
            if (target != null && target.getNodeType() == NodeType.POINT && !chosen.isEmpty()) {
                Notification.show("「" + target.getName() + "」是功能点：节点不能挂在功能点下（只选用例时可以）");
                return;
            }
            if (newParentId == null && !chosenCases.isEmpty()) {
                Notification.show("用例要挂在某个节点下，不能直接挂到 Root");
                return;
            }
            for (TreeNode node : chosen) {
                String previousParent = node.getParentId();
                node.setParentId(newParentId);
                if (!java.util.Objects.equals(previousParent, newParentId)) node.setSortOrder(nextSortOrder(newParentId));
            }
            for (TestCase testCase : chosenCases) testCase.setPointId(newParentId);
            workspace = workspaceService.save(workspace);
            dialog.close();
            refreshTree();
            saved("已移动 " + (chosen.isEmpty() ? "" : chosen.size() + " 个节点")
                    + (!chosen.isEmpty() && !chosenCases.isEmpty() ? "、" : "")
                    + (chosenCases.isEmpty() ? "" : chosenCases.size() + " 条用例")
                    + " 到 " + (newParentId == null ? "Root /" : (target == null ? targetId : target.getName())));
        });
        dialog.open();
    }

    private void renderPicker(FunctionTree picker, Set<String> excluded, String[] picked) {
        TreeView view = buildTreeView(excluded, picked[0], false, false);
        picker.render(view.roots(), view.children());
    }

    private void collectDescendantIds(String nodeId, Set<String> out) {
        for (TreeNode child : children.getOrDefault(nodeId, List.of())) {
            out.add(child.getId());
            collectDescendantIds(child.getId(), out);
        }
    }

    private List<TreeNode> selectedTreeNodes() {
        if (workspace == null) return List.of();
        Set<String> chosen = effectiveTreeIds();
        return workspace.nodes.stream()
                .filter(n -> chosen.contains(n.getId()) && n.getDeletedAt() == null)
                .toList();
    }

    /** 选法里的用例行（"case:xxx"）：Ctrl/Shift 点选和勾选框都算。 */
    private List<TestCase> selectedTreeCases() {
        return treeCases(effectiveTreeIds());
    }

    /** 只要 Ctrl/Shift 点选出来的用例（不含勾选框），导出范围用它，免得和「已勾选用例」重复。 */
    private List<TestCase> pointSelectedTreeCases() {
        return casesUnder(selectedTreeIds);
    }

    /**
     * 勾选框勾中的用例：勾了功能点/目录就带上它下面全部用例，直接勾了用例行就是那一条。
     * 和点选集无关——「导出已勾选用例」只认勾选框。
     */
    private List<TestCase> checkedTreeCases() {
        return casesUnder(checkedTreeIds);
    }

    /** 一组树 id（节点或用例行）下面覆盖的用例：节点会展开到它的全部子孙用例。 */
    private List<TestCase> casesUnder(Set<String> ids) {
        if (workspace == null || ids == null || ids.isEmpty()) return List.of();
        Set<String> caseIds = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null) continue;
            if (id.startsWith("case:")) caseIds.add(id.substring("case:".length()));
            else collectCaseIdsUnder(id, caseIds);
        }
        return caseIds.stream()
                .map(caseId -> workspace.testCases.stream()
                        .filter(c -> caseId.equals(c.getId()) && c.getDeletedAt() == null)
                        .findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private void collectCaseIdsUnder(String nodeId, Set<String> out) {
        for (TreeNode child : children.getOrDefault(nodeId, List.of())) {
            String id = child.getId();
            if (id == null) continue;
            if (id.startsWith("case:")) out.add(id.substring("case:".length()));
            else collectCaseIdsUnder(id, out);
        }
    }

    private List<TestCase> treeCases(Set<String> ids) {
        if (workspace == null || ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .filter(id -> id != null && id.startsWith("case:"))
                .map(id -> id.substring("case:".length()))
                .distinct()
                .map(caseId -> workspace.testCases.stream()
                        .filter(c -> caseId.equals(c.getId()) && c.getDeletedAt() == null)
                        .findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private boolean isSynthetic(TreeNode node) {
        String id = node == null ? null : node.getId();
        return id != null && (id.startsWith("case:") || "unbound".equals(id));
    }

    private void showNode(TreeNode node) {
        if (node == null) return;
        if (node.getId() != null && node.getId().startsWith("case:")) {
            String caseId = node.getId().substring("case:".length());
            workspace.testCases.stream().filter(c -> caseId.equals(c.getId())).findFirst().ifPresent(this::openCaseEditor);
            return;
        }
        selectedNode = node;
        renderNodeDetail(node);
    }

    private void renderNodeDetail(TreeNode node) {
        casePreview.removeAll();
        Div title = new Div(node.getName());
        title.addClassName("case-detail-title");
        Div meta = new Div(node.getNodeType() == NodeType.POINT ? "功能点" : "目录"
                + " · " + workspace.testCases.stream().filter(c -> node.getId().equals(c.getPointId())).count() + " 条用例");
        meta.addClassName("muted");
        casePreview.add(title, meta);
        if (node.getNodeType() == NodeType.POINT) {
            Button addCase = primary("新增用例", VaadinIcon.PLUS, () -> openCaseEditor(null));
            addCase.addClassName("node-action");
            casePreview.add(addCase);
        }
        workspace.testCases.stream()
                .filter(c -> node.getId().equals(c.getPointId()) && c.getDeletedAt() == null)
                .forEach(testCase -> casePreview.add(caseCard(testCase)));
        if (workspace.testCases.stream().noneMatch(c -> node.getId().equals(c.getPointId()))) {
            Div empty = new Div("该节点下暂无用例。选择功能点后可手动新增，或在发现元素后点击“生成用例”。");
            empty.addClassName("muted");
            casePreview.add(empty);
        }
    }

    private Div caseCard(TestCase testCase) {
        Div card = new Div();
        card.addClassName("case-card");
        Span title = new Span((testCase.getCaseNo() == null ? "" : com.yuceyang.uitest.testcase.CaseNumbers.normalize(testCase.getCaseNo()) + " ") + testCase.getTitle());
        title.addClassName("case-title");
        Span status = new Span(caseStatusName(testCase.getStatus()));
        status.addClassName("case-status");
        status.addClassName("status-" + testCase.getStatus().toLowerCase());
        Div meta = new Div(joinText(" · ", testCase.getModuleName(), testCase.getPriority(),
                categoryLabel(testCase.getCategory())));
        meta.addClassName("muted");
        Div actions = new Div();
        actions.addClassName("case-actions");
        actions.add(button("详情", VaadinIcon.EYE, () -> openCaseEditor(testCase)),
                button("通过", VaadinIcon.CHECK, () -> setCaseStatus(testCase, "PASS")),
                button("失败", VaadinIcon.CLOSE, () -> setCaseStatus(testCase, "FAIL")));
        if (testCase.getScreenshotId() != null) {
            actions.add(button("截图", VaadinIcon.PICTURE, () -> openScreenshotPreview(testCase.getScreenshotId())));
        }
        card.add(title, status, meta, actions);
        return card;
    }

    private void openScreenshotPreview(String screenshotId) {
        Screenshot screenshot = workspace.screenshots.stream()
                .filter(s -> screenshotId.equals(s.getId())).findFirst().orElse(null);
        if (screenshot == null) { Notification.show("截图不存在或已删除"); return; }
        Dialog dialog = dialog("用例截图");
        dialog.setWidth("860px");
        Image image = new Image(workspaceService.screenshotData(screenshot), screenshot.getRelativePath());
        image.setWidth("100%");
        dialog.add(new VerticalLayout(image,
                new Span(screenshot.getWidth() + " × " + screenshot.getHeight() + " · " + screenshot.getRelativePath())));
        dialog.getFooter().add(new Button("关闭", e -> dialog.close()));
        dialog.open();
    }

    private void setCaseStatus(TestCase testCase, String status) {
        testCase.setStatus(status);
        workspace = workspaceService.save(workspace);
        refreshTree();
        if (selectedNode != null) renderNodeDetail(selectedNode);
        saved("用例状态已更新");
    }

    private void openCaseEditor(TestCase existing) {
        boolean pending = existing != null && pendingCases.contains(existing);
        String pointId = existing != null ? existing.getPointId()
                : selectedNode == null ? null : selectedNode.getId();
        if (!pending && (pointId == null || (existing == null
                && (selectedNode == null || selectedNode.getNodeType() != NodeType.POINT)))) {
            Notification.show("请先在功能点树中选择一个功能点");
            return;
        }
        Dialog dialog = dialog(existing == null ? "新增测试用例"
                : (pending ? "用例详情（未入库）" : "测试用例详情 · 功能点操作"));
        dialog.setWidth("880px");
        TextField title = requiredField("用例标题");
        TextField module = new TextField("模块");
        ComboBox<String> priority = new ComboBox<>("优先级");
        priority.setItems("P0", "P1", "P2", "P3");
        ComboBox<String> category = new ComboBox<>("分类");
        category.setItems("正向", "异常", "边界", "权限", "性能");
        TextField design = new TextField("设计需求");
        TextField track = new TextField("测试跟踪");
        TextField params = new TextField("携带参数 / 测试数据");
        TextField l1 = new TextField("一级标题");
        TextField l2 = new TextField("二级标题");
        TextField method = new TextField("请求方式");
        TextField url = new TextField("URL");
        TextField systemName = new TextField("系统名字");
        systemName.setReadOnly(true);
        systemName.setValue(systemSelect.getValue() == null ? "" : systemSelect.getValue().getName());
        TextArea preconditions = new TextArea("前提与约束");
        TextArea steps = new TextArea("输入及操作说明");
        TextArea expected = new TextArea("期望测试结果");
        TextArea remark = new TextArea("备注 / 优先级");
        TextArea annotation = new TextArea("问题标注");
        ComboBox<String> status = new ComboBox<>("执行状态");
        status.setItems("待执行", "通过", "失败");
        for (TextArea area : List.of(preconditions, steps, expected)) { area.setWidthFull(); area.setHeight("92px"); }
        remark.setWidthFull();
        remark.setHeight("60px");
        annotation.setWidthFull();
        annotation.setHeight("60px");
        module.setWidthFull();
        design.setWidthFull();
        track.setWidthFull();
        params.setWidthFull();
        url.setWidthFull();

        String systemId = systemSelect.getValue() == null ? null : systemSelect.getValue().getId();
        List<Screenshot> shots = workspace.screenshots.stream()
                .filter(s -> s.getDeletedAt() == null && systemId != null && systemId.equals(s.getSystemId()))
                .filter(s -> s.getStatus() == ScreenshotStatus.READY || s.getStatus() == ScreenshotStatus.BOUND)
                .sorted(Comparator.comparing(Screenshot::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .toList();
        ComboBox<Screenshot> shot = new ComboBox<>("关联截图");
        shot.setItems(shots);
        shot.setItemLabelGenerator(s -> s.getRelativePath() + " · " + s.getWidth() + "×" + s.getHeight());
        shot.setWidthFull();
        Image preview = new Image();
        preview.setWidth("100%");
        preview.setVisible(false);
        shot.addValueChangeListener(event -> showShotPreview(preview, event.getValue()));

        if (existing != null) {
            title.setValue(existing.getTitle());
            module.setValue(existing.getModuleName() == null ? defaultModule(pointId) : existing.getModuleName());
            priority.setValue(existing.getPriority() == null ? "P2" : existing.getPriority());
            category.setValue(categoryLabel(existing.getCategory()));
            design.setValue(nullSafe(existing.getDesignRequirement()));
            track.setValue(nullSafe(existing.getTestTrack()));
            params.setValue(nullSafe(existing.getParamsText()));
            preconditions.setValue(nullSafe(existing.getPreconditions()));
            steps.setValue(nullSafe(existing.getSteps()));
            expected.setValue(nullSafe(existing.getExpectedResult()));
            remark.setValue(nullSafe(existing.getRemark()));
            annotation.setValue(nullSafe(existing.getAnnotation()));
            l1.setValue(nullSafe(existing.getL1()));
            l2.setValue(nullSafe(existing.getL2()));
            method.setValue(existing.getMethod() == null ? "GET" : existing.getMethod());
            url.setValue(nullSafe(existing.getUrl()));
            status.setValue(caseStatusName(existing.getStatus()));
            if (existing.getScreenshotId() != null) {
                shots.stream().filter(s -> s.getId().equals(existing.getScreenshotId())).findFirst().ifPresent(shot::setValue);
            }
        } else {
            module.setValue(defaultModule(pointId));
            priority.setValue("P2");
            category.setValue("正向");
            track.setValue(defaultTrack(pointId));
            l1.setValue("/");
            l2.setValue("/");
            method.setValue("GET");
            url.setValue(address.getValue() == null ? "" : address.getValue().trim());
            design.setValue("支持通过界面操作对当前功能点进行查询/展示验证");
            preconditions.setValue("已登录被测系统，进入对应功能页面。");
            steps.setValue("1. 打开目标页面；\n2. 定位并操作对应控件；\n3. 观察页面反馈（只读）。");
            expected.setValue("页面按需求正常响应，数据展示正确，无异常提示。");
            status.setValue("待执行");
        }
        showShotPreview(preview, shot.getValue());

        HorizontalLayout line1 = new HorizontalLayout(module, priority, category, status);
        line1.setWidthFull();
        line1.expand(module);
        HorizontalLayout line2 = new HorizontalLayout(design, track);
        line2.setWidthFull();
        line2.expand(design);
        HorizontalLayout line3 = new HorizontalLayout(systemName, l1, l2);
        line3.setWidthFull();
        line3.expand(systemName);
        HorizontalLayout line4 = new HorizontalLayout(method, url);
        line4.setWidthFull();
        line4.expand(url);
        VerticalLayout body = new VerticalLayout(title, line1, line2, line3, line4, params,
                preconditions, steps, expected, remark, annotation, shot, preview);
        body.setPadding(false);
        body.setSpacing(true);
        dialog.add(body);
        footer(dialog, pending ? "保存预览" : "保存", () -> {
            TestCase testCase = existing == null
                    ? new TestCase(workspace.projectId, systemSelect.getValue().getId(), pointId, title.getValue())
                    : existing;
            if (existing == null) testCase.onCreateForStore();
            testCase.setTitle(title.getValue());
            testCase.setModuleName(module.getValue());
            testCase.setPriority(priority.getValue());
            testCase.setCategory(categoryCode(category.getValue()));
            testCase.setDesignRequirement(design.getValue());
            testCase.setTestTrack(track.getValue());
            testCase.setParamsText(params.getValue());
            testCase.setPreconditions(preconditions.getValue());
            testCase.setSteps(steps.getValue());
            testCase.setExpectedResult(expected.getValue());
            testCase.setRemark(remark.getValue());
            testCase.setAnnotation(annotation.getValue());
            testCase.setL1(l1.getValue());
            testCase.setL2(l2.getValue());
            testCase.setMethod(method.getValue());
            testCase.setUrl(url.getValue());
            testCase.setScreenshotId(shot.getValue() == null ? null : shot.getValue().getId());
            testCase.setStatus(switch (status.getValue()) {
                case "通过" -> "PASS"; case "失败" -> "FAIL"; default -> "DRAFT";
            });
            if (pending) {
                dialog.close();
                renderPendingCases("预览已更新", "");
                return;
            }
            if (existing == null) workspace.testCases.add(testCase);
            workspace = workspaceService.save(workspace);
            dialog.close(); refreshTree();
            TreeNode point = workspace.nodes.stream().filter(n -> pointId.equals(n.getId())).findFirst().orElse(null);
            if (point != null) { selectedNode = point; renderNodeDetail(point); }
            saved("测试用例已保存");
        });
        dialog.open();
    }

    private void showShotPreview(Image preview, Screenshot screenshot) {
        if (screenshot == null) { preview.setVisible(false); return; }
        try {
            preview.setSrc(workspaceService.screenshotData(screenshot));
            preview.setVisible(true);
        } catch (RuntimeException e) {
            preview.setVisible(false);
        }
    }

    private String defaultModule(String pointId) {
        List<String> path = nodePath(pointId);
        return path.isEmpty() ? systemName(systemSelect.getValue() == null ? null : systemSelect.getValue().getId())
                : path.getFirst();
    }

    private String defaultTrack(String pointId) {
        List<String> path = nodePath(pointId);
        return path.isEmpty() ? "" : String.join("-", path);
    }

    private static String categoryLabel(String code) {
        if (code == null) return "正向";
        return switch (code) {
            case "negative" -> "异常";
            case "boundary" -> "边界";
            case "auth" -> "权限";
            case "performance" -> "性能";
            default -> "正向";
        };
    }

    private static String categoryCode(String label) {
        if (label == null) return "positive";
        return switch (label) {
            case "异常" -> "negative";
            case "边界" -> "boundary";
            case "权限" -> "auth";
            case "性能" -> "performance";
            default -> "positive";
        };
    }

    private String shotLabel(String screenshotId) {
        if (screenshotId == null || screenshotId.isBlank() || workspace == null) return "";
        return workspace.screenshots.stream()
                .filter(s -> screenshotId.equals(s.getId()))
                .map(Screenshot::getRelativePath).findFirst().orElse("");
    }

    private static String joinText(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(part);
        }
        return sb.toString();
    }

    private String caseStatusName(String status) {
        return switch (status) { case "PASS" -> "通过"; case "FAIL" -> "失败"; default -> "待执行"; };
    }

    private void discoverElements() {
        if (sessionId == null) { Notification.show("请先输入地址并前往目标系统"); return; }
        try {
            discoveredElements = workspaceService.discoverElements(sessionId);
            workspace = workspaceService.getProject(workspace.projectId);
            elementHints.clear();
            applyElementFilter();
            drawOverlay();
            Notification.show("发现 " + discoveredElements.size() + " 个可交互元素，点「AI 勾选」让模型判断哪些值得测");
        } catch (RuntimeException e) { showError(e); }
    }

    /**
     * AI 勾选：让模型逐个判断元素——值得测的自动勾上；噪声（分页/重复/装饰）保留展示但不勾；
     * 识别错误或根本不是用户意图的（拼接文本、布局容器）直接从列表删掉；名称识别错的用模型给出的名称修正。
     */
    private void aiCheckElements() {
        if (discoveredElements.isEmpty()) { Notification.show("请先点「发现元素」"); return; }
        if (aiChecking) { Notification.show("AI 正在判断，请稍候"); return; }
        aiChecking = true;
        List<ElementCandidate> snapshot = List.copyOf(discoveredElements);
        UI ui = UI.getCurrent();
        String systemId = systemSelect.getValue() == null ? null : systemSelect.getValue().getId();
        CompletableFuture.supplyAsync(() -> elementAdvisor.advise(snapshot)).whenComplete((hints, error) -> {
            if (ui == null) return;
            try {
                ui.access(() -> {
                    aiChecking = false;
                    if (workspace == null || systemSelect.getValue() == null
                            || !systemSelect.getValue().getId().equals(systemId)) return;
                    if (error != null) {
                        log.warn("AI 勾选失败：{}", rootMessage(error));
                        Notification.show("AI 勾选失败：" + rootMessage(error));
                        return;
                    }
                    applyAiCheck(hints);
                });
            } catch (RuntimeException e) {
                aiChecking = false;
                log.warn("AI 勾选结果回推界面失败", e);
            }
        });
    }

    private void applyAiCheck(Map<String, ElementAdvisor.Hint> hints) {
        elementHints.clear();
        elementHints.putAll(hints);
        selectedElementIds.clear();
        List<ElementCandidate> kept = new ArrayList<>();
        int testable = 0, ignored = 0, dropped = 0, renamed = 0, existing = 0;
        for (ElementCandidate element : discoveredElements) {
            ElementAdvisor.Hint hint = hints.get(element.getId());
            // 已经在功能点树里的（按元素 id 或同名功能点）不参与勾选，也不改名，避免和树里对不上。
            boolean inTree = elementInTree(element);
            if (hint == null) { kept.add(element); if (inTree) existing++; continue; }
            if (hint.drop() && !inTree) { dropped++; continue; }
            String corrected = hint.correctedName();
            if (!inTree && corrected != null && !corrected.isBlank() && !corrected.equals(element.getName())) {
                element.setName(corrected);
                renamed++;
            }
            kept.add(element);
            if (inTree) existing++;
            else if (hint.suggested()) { selectedElementIds.add(element.getId()); testable++; }
            else ignored++;
        }
        discoveredElements = kept;
        applyElementFilter();
        drawOverlay();
        Notification.show("AI 勾选完成：勾选 " + testable + " 个，保留未勾选 " + ignored + " 个"
                + (existing > 0 ? "，已在功能点树中 " + existing + " 个（不重复勾选）" : "")
                + (dropped > 0 ? "，删除误识别 " + dropped + " 个" : "")
                + (renamed > 0 ? "，修正名称 " + renamed + " 个" : ""));
    }

    private void applyElementFilter() {
        String text = elementFilter.getValue() == null ? "" : elementFilter.getValue().trim().toLowerCase();
        String kind = elementKind.getValue();
        List<ElementCandidate> filtered = discoveredElements.stream()
                .filter(e -> text.isBlank() || (e.getName() + " " + e.getSelector()).toLowerCase().contains(text))
                .filter(e -> kind == null || "全部类型".equals(kind) || kindName(e).equals(kind))
                .toList();
        elementGrid.setItems(filtered);
        elementCount.setText(Integer.toString(filtered.size()));
        syncElementSelectAll();
    }

    /** 预筛建议列：建议 / 忽略（悬停看理由），归组时显示组名。 */
    private Span elementAdvice(ElementCandidate item) {
        if (elementInTree(item)) {
            Span done = new Span("已入库");
            done.addClassName("advice");
            done.addClassName("advice-test");
            done.getElement().setAttribute("title", "已在功能点树中，忽略本次重复标记");
            return done;
        }
        ElementAdvisor.Hint hint = elementHints.get(item.getId());
        if (hint == null) {
            Span none = new Span("未预筛");
            none.addClassName("advice-none");
            return none;
        }
        Span span = new Span(hint.suggested() ? "建议" : "忽略");
        span.addClassName("advice");
        if (hint.suggested()) span.addClassName("advice-test"); else span.addClassName("advice-skip");
        String reason = hint.reason() == null ? "" : hint.reason();
        if (hint.group() != null) reason = (reason.isBlank() ? "" : reason + "；") + "归组：" + hint.group();
        span.getElement().setAttribute("title", reason);
        return span;
    }

    private Component elementCheckbox(ElementCandidate item) {
        if (elementInTree(item)) {
            // 已经在功能点树里了（按元素 id 或同名功能点去重）：这一行不能再勾，直接说清楚，
            // 而不是给一个灰到看不见的复选框——那会被当成「全选没生效」。
            Span done = new Span("已入库");
            done.addClassName("el-in-tree");
            done.getElement().setAttribute("title", "该元素已入库到功能点树（按元素或名称去重），无需重复标记");
            return done;
        }
        Checkbox checkbox = new Checkbox();
        checkbox.setValue(selectedElementIds.contains(item.getId()));
        checkbox.addValueChangeListener(event -> {
            if (event.getValue()) selectedElementIds.add(item.getId()); else selectedElementIds.remove(item.getId());
            syncElementSelectAll();
        });
        return checkbox;
    }

    /** 表头全选：只对「还没入库、可以勾」的行生效，并跟着下面的勾选状态走。 */
    private Component elementSelectAllHeader() {
        elementSelectAll = new Checkbox();
        elementSelectAll.getElement().setAttribute("title", "全选还没入库的元素（已入库的行不参与）");
        elementSelectAll.addValueChangeListener(event -> {
            if (!event.isFromClient()) return;
            if (Boolean.TRUE.equals(event.getValue())) selectAllElements(); else clearElementSelection();
        });
        HorizontalLayout line = new HorizontalLayout(elementSelectAll, new Span("选择"));
        line.setAlignItems(Alignment.CENTER);
        line.setSpacing(false);
        line.addClassName("el-select-head");
        return line;
    }

    /** 表头复选框跟随当前可勾行：全选 → 勾上；一个没勾 → 空；部分 → 半选。 */
    private void syncElementSelectAll() {
        if (elementSelectAll == null) return;
        List<ElementCandidate> items = elementGrid.getListDataView().getItems().toList();
        int selectable = 0;
        int selected = 0;
        for (ElementCandidate item : items) {
            if (elementInTree(item)) continue;
            selectable++;
            if (selectedElementIds.contains(item.getId())) selected++;
        }
        elementSelectAll.setEnabled(selectable > 0);
        elementSelectAll.setValue(selectable > 0 && selected == selectable);
        elementSelectAll.setIndeterminate(selected > 0 && selected < selectable);
    }

    /**
     * 该元素是否已经入过库：树节点挂着它的 elementId，或者树里已有同名的功能点
     * （很多节点是 AI 自动建树/手工建的，没有 elementId，靠名称去重才算真正不重复）。
     */
    private boolean elementInTree(ElementCandidate element) {
        if (workspace == null || element == null) return false;
        String systemId = systemSelect.getValue() == null ? null : systemSelect.getValue().getId();
        String name = element.getName() == null ? "" : element.getName().trim();
        // 同名元素不止一个时（左右两栏各一个「开始时间」），名称不能代表「这个控件已经入库」，
        // 否则标记了左边那个，右边那个就被判成已入库、再也勾不上。这时只认元素 id。
        boolean sharedName = discoveredElements.stream()
                .anyMatch(other -> other != element && !other.getId().equals(element.getId())
                        && name.equals(nullSafe(other.getName()).trim()));
        return workspace.nodes.stream()
                .filter(n -> n.getDeletedAt() == null)
                .filter(n -> systemId == null || systemId.equals(n.getSystemId()))
                .anyMatch(n -> (element.getId() != null && element.getId().equals(n.getElementId()))
                        || (!sharedName && n.getNodeType() == NodeType.POINT && !name.isEmpty()
                            && name.equals(n.getName() == null ? "" : n.getName().trim())));
    }

    private void selectAllElements() {
        elementGrid.getListDataView().getItems()
                .filter(item -> !elementInTree(item))
                .map(ElementCandidate::getId).forEach(selectedElementIds::add);
        elementGrid.getDataProvider().refreshAll();
        syncElementSelectAll();
        drawOverlay();
    }

    private void clearElementSelection() {
        selectedElementIds.clear();
        elementGrid.getDataProvider().refreshAll();
        syncElementSelectAll();
        drawOverlay();
    }

    private void clearDiscoveredElements() {
        discoveredElements = new ArrayList<>();
        selectedElementIds.clear();
        elementGrid.setItems(List.of());
        elementCount.setText("0");
        syncElementSelectAll();
        drawOverlay();
        Notification.show("交互元素列表已清空，已入库的功能点不受影响");
    }

    private void clearAllMarks() {
        if (workspace == null || systemSelect.getValue() == null) return;
        String systemId = systemSelect.getValue().getId();
        int marks = 0;
        for (ElementCandidate element : workspace.elements) {
            if (!systemId.equals(element.getSystemId()) || element.getDeletedAt() != null || !element.isConfirmed()) continue;
            element.setConfirmed(false);
            element.setDeletedAt(java.time.Instant.now());
            marks++;
        }
        if (marks == 0) { Notification.show("当前系统没有已标记的元素"); return; }
        int removedNodes = 0;
        for (TreeNode node : workspace.nodes) {
            if (!systemId.equals(node.getSystemId()) || node.getDeletedAt() != null || node.getElementId() == null) continue;
            boolean hasCases = workspace.testCases.stream()
                    .anyMatch(c -> node.getId().equals(c.getPointId()) && c.getDeletedAt() == null);
            if (hasCases) continue;
            node.setDeletedAt(java.time.Instant.now());
            removedNodes++;
        }
        workspace = workspaceService.save(workspace);
        selectedElementIds.clear();
        refreshTree();
        drawOverlay();
        saved("已取消 " + marks + " 个元素标记" + (removedNodes > 0 ? "，移除 " + removedNodes + " 个无用例的功能点节点" : ""));
    }

    private void openElementEditor(ElementCandidate item) {
        Dialog dialog = dialog("编辑交互元素");
        dialog.setWidth("420px");
        TextField name = requiredField("名称"); name.setValue(item.getName());
        ComboBox<String> kind = new ComboBox<>("类型");
        kind.setItems("按钮", "输入框", "下拉框", "链接", "勾选", "上传", "其他");
        kind.setValue(kindName(item));
        dialog.add(formBody(name, kind));
        footer(dialog, "保存", () -> {
            item.setName(name.getValue());
            item.setKind(switch (kind.getValue()) {
                case "按钮" -> "button"; case "输入框" -> "input"; case "下拉框" -> "select";
                case "链接" -> "link"; case "勾选" -> "check"; case "上传" -> "file"; default -> "other";
            });
            if (workspace.elements.stream().anyMatch(e -> e.getId().equals(item.getId()))) {
                workspaceService.save(workspace);
            }
            dialog.close(); applyElementFilter(); saved("交互元素已更新");
        });
        dialog.open();
    }

    private void openBulkMarkDialog() {
        List<ElementCandidate> chosen = discoveredElements.stream()
                .filter(e -> selectedElementIds.contains(e.getId()))
                .filter(e -> !elementInTree(e))
                .toList();
        if (chosen.isEmpty()) { Notification.show("请先勾选交互元素"); return; }
        Dialog dialog = dialog("批量标记");
        dialog.setWidth("640px");
        Div preview = new Div();
        preview.addClassName("muted");
        String systemId = systemSelect.getValue().getId();
        ComboBox<NodeType> markType = new ComboBox<>("标记为");
        markType.setItems(NodeType.values());
        markType.setItemLabelGenerator(this::typeName);
        markType.setValue(NodeType.POINT);
        markType.setWidth("180px");
        markType.setAllowCustomValue(false);
        Runnable refreshPreview = () -> preview.setText("将标记 " + chosen.size() + " 个元素为「"
                + typeName(markType.getValue()) + "」：" + chosen.stream().map(ElementCandidate::getName).limit(6).toList());
        refreshPreview.run();
        markType.addValueChangeListener(event -> refreshPreview.run());
        Span tip = new Span("选择挂载位置：点 Root「/」= 顶层；点任意节点 = 挂到其下。默认未选时为顶层。");
        tip.addClassName("muted");
        final String[] pickedParent = {null};
        Span pickedLabel = new Span("挂载位置：Root /（顶层）");
        pickedLabel.addClassName("picked-target");
        FunctionTree[] holder = new FunctionTree[1];
        FunctionTree parentTree = new FunctionTree(new FunctionTree.Handler() {
            @Override public void toggle(String id) {
                if (id == null) return;
                if (!collapsedTreeIds.remove(id)) collapsedTreeIds.add(id);
                renderPicker(holder[0], Set.of(), pickedParent);
            }
            @Override public void select(String id, boolean ctrl, boolean shift) {
                pickedParent[0] = id;
                pickedLabel.setText("挂载位置：" + parentLabel(id));
                renderPicker(holder[0], Set.of(), pickedParent);
            }
            @Override public void check(String id) { select(id, false, false); }
            @Override public void open(String id) { select(id, false, false); }
            @Override public void move(String draggedId, String targetId) { }
        });
        holder[0] = parentTree;
        parentTree.setHeight("260px");
        parentTree.addClassName("ft-picker");
        renderPicker(parentTree, Set.of(), pickedParent);
        dialog.add(formBody(preview, markType, tip, pickedLabel, parentTree));
        footer(dialog, "确认标记", () -> {
            NodeType targetType = markType.getValue() == null ? NodeType.POINT : markType.getValue();
            String parentId = pickedParent[0] == null || "ft-root".equals(pickedParent[0]) ? null : pickedParent[0];
            int created = 0;
            // 先按预筛给出的归组信息合并：同一组的元素（如多组时间选择框）只生成一个功能点。
            Map<String, List<ElementCandidate>> groups = new LinkedHashMap<>();
            for (ElementCandidate element : chosen) {
                ElementAdvisor.Hint hint = elementHints.get(element.getId());
                String group = hint == null ? null : hint.group();
                String key = group == null || group.isBlank() ? "one:" + element.getId() : "group:" + group;
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(element);
            }
            for (Map.Entry<String, List<ElementCandidate>> entry : groups.entrySet()) {
                List<ElementCandidate> members = entry.getValue();
                ElementCandidate first = members.getFirst();
                for (ElementCandidate element : members) {
                    element.setProjectId(workspace.projectId);
                    element.setSystemId(systemId);
                    element.setConfirmed(true);
                    element.setArchived(false);
                    element.setDeletedAt(null);
                    boolean alreadyStored = workspace.elements.stream().anyMatch(e -> e.getId().equals(element.getId()));
                    if (!alreadyStored) workspace.elements.add(element);
                }
                boolean grouped = entry.getKey().startsWith("group:") && members.size() > 1;
                String nodeName = grouped ? entry.getKey().substring("group:".length()) : first.getName();
                // 同一元素/组在同一挂载位置下只生成一个同类型节点；已存在则复用。
                TreeNode reuse = null;
                for (ElementCandidate element : members) {
                    reuse = findNodeForElement(element, targetType, parentId);
                    if (reuse != null) break;
                }
                if (reuse == null) {
                    // 同名节点已经绑了另一个元素（同一页两个「开始时间」）时不能复用，
                    // 否则一次标记两个相同控件，第二个会被静默并进第一个节点里。
                    TreeNode sameName = findNodeByName(parentId, targetType, nodeName);
                    if (sameName != null && (sameName.getElementId() == null
                            || sameName.getElementId().equals(first.getId()))) {
                        reuse = sameName;
                    }
                }
                if (reuse == null) {
                    TreeNode node = new TreeNode(workspace.projectId, systemId, parentId, targetType, nodeName);
                    node.setSortOrder(nextSortOrder(parentId));
                    node.setElementId(first.getId());
                    node.onCreateForStore();
                    workspace.nodes.add(node);
                    created++;
                } else if (!grouped) {
                    // 复用的节点同步最新名称，避免元素改名后树里还是旧名字。
                    reuse.setName(first.getName());
                }
            }
            workspace = workspaceService.save(workspace);
            if (autoShot.getValue() && sessionId != null) {
                try { workspaceService.captureCurrent(sessionId); workspace = workspaceService.getProject(workspace.projectId); }
                catch (RuntimeException ignored) { }
            }
            dialog.close(); refreshTree(); selectedElementIds.clear();
            elementGrid.getDataProvider().refreshAll();
            syncElementSelectAll();
            drawOverlay();
            String typeLabel = typeName(targetType);
            saved(created == 0 ? "已标记 " + chosen.size() + " 个元素（同名" + typeLabel + "已存在，直接复用）"
                    : "已标记 " + chosen.size() + " 个元素，新建 " + created + " 个" + typeLabel);
        });
        dialog.open();
    }

    /**
     * 找出元素已对应的功能点节点：先按元素 id，再按指纹。
     * 元素改名或定位漂移后指纹会变、候选会重建，只按 id 判断会在树里留下同名重复节点。
     */
    private TreeNode findNodeForElement(ElementCandidate element, NodeType type, String parentId) {
        if (workspace == null || element == null) return null;
        TreeNode same = workspace.nodes.stream()
                .filter(n -> n.getDeletedAt() == null && n.getNodeType() == type
                        && java.util.Objects.equals(parentId, n.getParentId())
                        && element.getId().equals(n.getElementId()))
                .findFirst().orElse(null);
        if (same != null) return same;
        String fingerprint = element.getFingerprint();
        if (fingerprint == null) return null;
        for (TreeNode node : workspace.nodes) {
            if (node.getDeletedAt() != null || node.getElementId() == null) continue;
            if (node.getNodeType() != type) continue;
            if (element.getSystemId() != null && !element.getSystemId().equals(node.getSystemId())) continue;
            String ownerId = node.getElementId();
            boolean sameFingerprint = workspace.elements.stream()
                    .anyMatch(e -> ownerId.equals(e.getId()) && fingerprint.equals(e.getFingerprint()));
            if (sameFingerprint) {
                node.setElementId(element.getId());
                return node;
            }
        }
        return null;
    }

    /** 同一父级下同名同类型节点直接复用，避免重复标记时越堆越多。 */
    private TreeNode findNodeByName(String parentId, NodeType type, String name) {
        if (workspace == null || name == null) return null;
        return workspace.nodes.stream()
                .filter(n -> n.getDeletedAt() == null && n.getNodeType() == type
                        && java.util.Objects.equals(parentId, n.getParentId())
                        && name.equals(n.getName()))
                .findFirst().orElse(null);
    }

    private String parentLabel(String id) {
        if (id == null || "ft-root".equals(id)) return "Root /（顶层）";
        TreeNode node = findNode(id);
        return node == null ? id : node.getName() + "（" + typeName(node.getNodeType()) + "）";
    }

    /** 清理历史重复：同一父级下、由同一元素或同名元素生成的重复功能点只保留一个。 */
    private void cleanDuplicateNodes() {
        if (!requireWorkspace()) return;
        String systemId = systemSelect.getValue().getId();
        Map<String, TreeNode> keeper = new LinkedHashMap<>();
        List<TreeNode> duplicates = new ArrayList<>();
        for (TreeNode node : workspace.nodes) {
            if (node.getDeletedAt() != null || !systemId.equals(node.getSystemId())) continue;
            if (node.getNodeType() != NodeType.POINT || node.getElementId() == null) continue;
            String key = nullSafe(node.getParentId()) + "\u0001" + nullSafe(node.getName());
            TreeNode first = keeper.get(key);
            if (first == null) keeper.put(key, node);
            else duplicates.add(node);
        }
        // 顺带合并重复用例：同一功能点下标题相同的用例只保留最早的一条。
        Map<String, TestCase> keepCase = new LinkedHashMap<>();
        List<TestCase> duplicateCases = new ArrayList<>();
        for (TestCase testCase : workspace.testCases) {
            if (testCase.getDeletedAt() != null || !systemId.equals(testCase.getSystemId())) continue;
            String key = nullSafe(testCase.getPointId()) + "\u0001" + nullSafe(testCase.getTitle());
            if (keepCase.putIfAbsent(key, testCase) != null) duplicateCases.add(testCase);
        }
        if (duplicates.isEmpty() && duplicateCases.isEmpty()) {
            Notification.show("没有发现重复的功能点或用例");
            return;
        }
        Dialog dialog = dialog("清理重复功能点");
        dialog.setWidth("480px");
        Div info = new Div("发现 " + duplicates.size() + " 个重复功能点（同一上级、同一名称）、"
                + duplicateCases.size() + " 条重复用例（同一功能点、同一标题）。"
                + "清理时会把重复功能点上的用例移到保留节点，再删除重复节点与重复用例。");
        info.addClassName("muted");
        dialog.add(formBody(info));
        footer(dialog, "确认清理", () -> {
            int moved = 0;
            for (TestCase duplicate : duplicateCases) duplicate.setDeletedAt(java.time.Instant.now());
            for (TreeNode duplicate : duplicates) {
                String key = nullSafe(duplicate.getParentId()) + "\u0001" + nullSafe(duplicate.getName());
                TreeNode target = keeper.get(key);
                for (TestCase testCase : workspace.testCases) {
                    if (testCase.getDeletedAt() != null) continue;
                    if (duplicate.getId().equals(testCase.getPointId())) {
                        testCase.setPointId(target.getId());
                        moved++;
                    }
                }
                duplicate.setDeletedAt(java.time.Instant.now());
            }
            workspace = workspaceService.save(workspace);
            dialog.close();
            checkedTreeIds.clear(); explicitlyUnchecked.clear();
            refreshTree();
            saved("已清理 " + duplicates.size() + " 个重复功能点、" + duplicateCases.size() + " 条重复用例"
                    + (moved > 0 ? "，迁移 " + moved + " 条用例" : ""));
        });
        dialog.open();
    }

    private void openAutoTreePreview() {
        if (!requireWorkspace() || systemSelect.getValue() == null) { Notification.show("请先选择项目与系统"); return; }
        String systemId = systemSelect.getValue().getId();
        String systemName = systemSelect.getValue().getName();
        List<ElementCandidate> elements = discoveredElements.stream()
                .filter(e -> e.getSystemId() == null || systemId.equals(e.getSystemId()))
                .toList();
        if (elements.isEmpty()) { Notification.show("尚未发现组件目录，请先发现元素"); return; }
        boolean ai = treeAdvisor.aiAvailable();
        String url = currentUrl();

        Dialog dialog = dialog("自动建树（AI 生成，可编辑）");
        dialog.setWidth("860px");
        dialog.setHeight("86vh");
        Span status = new Span(ai ? "正在调用 AI 生成树，可以先把弹框拖开看后面的页面…" : "未配置模型，按元素语义与组件分组生成树…");
        status.addClassName("muted");
        Div summary = new Div();
        summary.addClassName("tree-draft-summary");
        DraftRows rows = new DraftRows();
        Div scroll = new Div(rows);
        scroll.addClassName("tree-draft-scroll");
        AttachBar attach = new AttachBar();
        List<DraftNode> drafts = new ArrayList<>();
        DraftEditor editor = new DraftEditor(drafts, rows, summary);
        VerticalLayout content = formBody(status, attach,
                hint("行内只需要改名、改类型；整批落点在上面的「挂到」里按功能点树选一次，草稿行会跟着缩进。行内 ⠿ 只用于在同一批里调父子；点一行再用 Ctrl/⌘ 点击其它行可以多选，Shift 连选一段，然后批量改类型。"),
                summary, editor.pickToolbar(), scroll);
        content.expand(scroll);
        dialog.add(content);
        editor.bind(attach);
        Button savePoints = button("只入功能点", VaadinIcon.FILTER, () ->
                saveTreeDraft(drafts, systemId, dialog, attach, true));
        Button saveAll = button("入库（含目录）", VaadinIcon.SITEMAP, () ->
                saveTreeDraft(drafts, systemId, dialog, attach, false));
        editor.onShapeChange(() -> syncDraftButtons(drafts, savePoints, saveAll, editor.effectiveCount()));
        Button toConsole = primary("生成用例 → 生成台", VaadinIcon.MAGIC, () ->
                saveAndGenerateDraft(drafts, systemId, dialog, attach, null, null));
        dialog.getFooter().add(new Button("取消", event -> dialog.close()), savePoints, saveAll, toConsole);
        dialog.open();

        UI ui = UI.getCurrent();
        CompletableFuture.supplyAsync(() -> treeAdvisor.propose(systemName, url, elements))
                .whenComplete((proposal, error) -> {
                    if (ui == null) return;
                    try {
                        ui.access(() -> {
                            if (error != null) {
                                log.warn("自动建树生成失败", error);
                                status.setText("生成失败：" + rootMessage(error) + "（可关掉重试）");
                                return;
                            }
                            drafts.clear();
                            for (TreeAdvisor.Proposition item : proposal) {
                                drafts.add(new DraftNode(item.id(), item.name(), item.type(), item.parentId(), item.elementId()));
                            }
                            status.setText((ai ? "AI 已" : "已") + "生成 " + drafts.size() + " 个节点，可改名/改类型/拖动换父级后再入库。");
                            editor.render();
                        });
                    } catch (Throwable e) {
                        log.warn("自动建树结果回推界面失败", e);
                    }
                });
    }

    /** 预览树里的一条草稿节点；只在弹框内存在，点「确认入库」才落库。 */
    static final class DraftNode {
        final String id;
        String name;
        NodeType type;
        String parentId;
        String elementId;
        /** 显示项：只读展示内容（仪表盘读数、状态、时间、链接），不建节点，生成时直接变成用例。 */
        boolean display;

        DraftNode(String id, String name, NodeType type, String parentId, String elementId) {
            this(id, name, type, parentId, elementId, false);
        }

        DraftNode(String id, String name, NodeType type, String parentId, String elementId, boolean display) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.parentId = parentId;
            this.elementId = elementId;
            this.display = display;
        }
    }

    /**
     * 草稿行类型下拉的一项：节点类型之外多一个「显示项」——展示型内容不是功能，不建节点，
     * 生成时直接按展示项出用例（读数、状态、时间、链接这类只读内容）。
     */
    record DraftType(String label, NodeType nodeType, boolean display) {
        static final DraftType MODULE = new DraftType("模块", NodeType.MODULE, false);
        static final DraftType TITLE1 = new DraftType("一级目录", NodeType.TITLE1, false);
        static final DraftType TITLE2 = new DraftType("二级目录", NodeType.TITLE2, false);
        static final DraftType TITLE3 = new DraftType("三级目录", NodeType.TITLE3, false);
        static final DraftType POINT = new DraftType("功能点", NodeType.POINT, false);
        static final DraftType DISPLAY = new DraftType("显示项（只出用例）", null, true);
        static final List<DraftType> ALL = List.of(MODULE, TITLE1, TITLE2, TITLE3, POINT, DISPLAY);

        static DraftType of(DraftNode node) {
            if (node.display) return DISPLAY;
            NodeType type = node.type == null ? NodeType.POINT : node.type;
            return ALL.stream().filter(item -> !item.display && item.nodeType == type)
                    .findFirst().orElse(POINT);
        }
    }

    /**
     * 一整批识别结果的落点选择：所有顶层草稿都挂到选中的已有节点下。
     * 选择器按功能点树的形态展开（实线缩进 + 类型），不塞进下拉里逐条挑。
     */
    private final class AttachBar extends VerticalLayout {
        private final Button choose = new Button();
        private final Span hint = new Span();
        private String rootParentId;
        private Runnable onChange = () -> {};
        /** 这批草稿是不是只出用例（全是显示项）：是的话落点可以直接选功能点。 */
        private boolean casesOnly;
        /** 用户在弹框里手动选过落点：之后不再自动跟着选中节点改。 */
        private boolean targetChosen;

        AttachBar() {
            setPadding(false);
            setSpacing(false);
            setWidthFull();
            HorizontalLayout line = new HorizontalLayout();
            line.setAlignItems(Alignment.CENTER);
            line.setWidthFull();
            Span label = new Span("挂到");
            label.addClassName("muted");
            choose.addClassName("attach-target");
            choose.getElement().setAttribute("title", "点开按功能点树的层级选择挂载目录");
            choose.addClickListener(event -> openPicker());
            hint.setText("整批节点挂到这个目录下；落点已经提供了它上面那几层，"
                    + "比它浅的目录行（模块、上级目录）和与它同名的那一行会自动略过，同级与更深的内容照常建");
            hint.addClassName("muted");
            line.add(label, choose, hint);
            line.expand(choose);
            line.setFlexGrow(0, label, hint);
            add(line);
            rootParentId = defaultAttachParent();
            refreshLabel();
        }

        /** 默认落点：当前选中的目录；选中的是功能点时取它最近的目录父级；都没有则顶层。 */
        private String defaultAttachParent() {
            Map<String, TreeNode> byId = nodesById();
            TreeNode cursor = selectedNode;
            Set<String> guard = new HashSet<>();
            while (cursor != null && guard.add(cursor.getId())) {
                if (cursor.getNodeType() != NodeType.POINT && findNode(cursor.getId()) != null) return cursor.getId();
                cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
            }
            return null;
        }

        void onChange(Runnable callback) { this.onChange = callback; }

        /** null = 顶层。 */
        String rootParentId() { return rootParentId; }

        /**
         * 草稿形状变了：全是显示项时落点允许是功能点（这批只出用例，用例本来就该挂在功能点下）；
         * 一旦又混进目录/功能点行，停在功能点上的落点要退回它上面的目录，否则节点会被挂到功能点下。
         */
        void updateCasesOnly(boolean value) {
            boolean changed = casesOnly != value;
            casesOnly = value;
            if (!changed) return;
            hint.setText(value
                    ? "这批只出用例：落点可以是目录，也可以是功能点，用例直接挂在它下面，不再新建节点"
                    : "整批节点挂到这个目录下；落点已经提供了它上面那几层，"
                            + "比它浅的目录行（模块、上级目录）和与它同名的那一行会自动略过，同级与更深的内容照常建");
            if (value) {
                if (!targetChosen && selectedNode != null && selectedNode.getNodeType() == NodeType.POINT
                        && findNode(selectedNode.getId()) != null) {
                    rootParentId = selectedNode.getId();
                    refreshLabel();
                }
            } else if (rootParentId != null) {
                TreeNode node = findNode(rootParentId);
                if (node != null && node.getNodeType() == NodeType.POINT) {
                    rootParentId = node.getParentId();
                    refreshLabel();
                }
            }
        }

        private void refreshLabel() {
            TreeNode node = rootParentId == null ? null : findNode(rootParentId);
            if (node == null) rootParentId = null;
            choose.setText(node == null ? "Root /（顶层）" : parentLabel(node, nodesById()) + "（" + typeName(node.getNodeType()) + "）");
        }

        private void openPicker() {
            Dialog dialog = dialog(casesOnly ? "挂到哪个节点" : "挂到哪个目录");
            dialog.setWidth("560px");
            Span tip = new Span(casesOnly
                    ? "这批只出用例：点 Root「/」= 顶层（会按区域名建一个承载功能点），"
                            + "点任意节点（含功能点）= 用例直接挂到它下面，不新增节点。"
                    : "整批节点都会挂到选中的目录下。点 Root「/」= 顶层；功能点下只能挂用例，不能作为落点。");
            tip.addClassName("muted");
            String[] picked = {rootParentId == null ? "ft-root" : rootParentId};
            FunctionTree[] holder = new FunctionTree[1];
            FunctionTree tree = new FunctionTree(new FunctionTree.Handler() {
                @Override public void toggle(String id) {
                    if (id == null) return;
                    if (!collapsedTreeIds.remove(id)) collapsedTreeIds.add(id);
                    renderPicker(holder[0], Set.of(), picked);
                }
                @Override public void select(String id, boolean ctrl, boolean shift) {
                    TreeNode target = id == null ? null : findNode(id);
                    if (target != null && target.getNodeType() == NodeType.POINT && !casesOnly) {
                        Notification.show("「" + target.getName() + "」是功能点：这批里还有目录/功能点行，节点不能挂在功能点下。"
                                + "如果这批只出用例，把要出用例的行设成「显示项（只出用例）」，功能点就能当落点了。");
                        return;
                    }
                    picked[0] = id;
                    renderPicker(holder[0], Set.of(), picked);
                }
                @Override public void check(String id) { select(id, false, false); }
                @Override public void open(String id) { select(id, false, false); }
                @Override public void move(String draggedId, String targetId) { }
            });
            holder[0] = tree;
            tree.setHeight("320px");
            tree.addClassName("ft-picker");
            // 只出用例时功能点是合法落点，别再用灰掉不可点的样式把人劝退。
            if (casesOnly) tree.addClassName("ft-picker-any");
            renderPicker(tree, Set.of(), picked);
            Span current = new Span();
            current.addClassName("picked-target");
            Runnable showCurrent = () -> current.setText("将挂到：" + parentLabel(picked[0]));
            showCurrent.run();
            dialog.add(formBody(tip, current, tree));
            footer(dialog, "挂到这里", () -> {
                String target = picked[0];
                rootParentId = target == null || "ft-root".equals(target) ? null : target;
                targetChosen = true;
                refreshLabel();
                dialog.close();
                onChange.run();
            });
            dialog.open();
        }
    }

    /**
     * 草稿行容器：负责 Ctrl/⌘ 点选与 Shift 连选的行内多选（和左侧功能点树同一套手感），供批量改类型。
     * 点击落在输入框、下拉、按钮上时是编辑动作，不算选中——除非按住 Ctrl/⌘ / Shift。
     */
    final class DraftRows extends VerticalLayout {
        /** 行被点中：ctrl = 加减选择，shift = 从上一次点中的行连选到这一行。 */
        interface PickHandler {
            void pick(String id, boolean ctrl, boolean shift);
        }

        private PickHandler pickHandler;

        DraftRows() {
            setPadding(false);
            setSpacing(false);
            addClassName("tree-draft-rows");
            getElement().executeJs("""
                    const root = this;
                    root.addEventListener('click', (e) => {
                      if (!e.target || !e.target.closest) return;
                      const row = e.target.closest('.tree-draft-row[data-id]');
                      if (!row || !root.contains(row)) return;
                      const ctrl = !!(e.ctrlKey || e.metaKey);
                      const shift = !!e.shiftKey;
                      // 行里几乎全是输入框：普通点击是编辑，带修饰键的点击才算是选中该行。
                      if (!ctrl && !shift && e.target.closest('vaadin-text-field,vaadin-combo-box,vaadin-button')) return;
                      if (ctrl || shift) e.preventDefault();
                      root.$server.pick(row.dataset.id, ctrl, shift);
                    });
                    """);
        }

        void onPick(PickHandler handler) {
            this.pickHandler = handler;
        }

        @ClientCallable
        public void pick(String id, boolean ctrl, boolean shift) {
            if (pickHandler != null) pickHandler.pick(id, ctrl, shift);
        }
    }

    /**
     * 建树预览的可编辑视图：改名、改类型、拖拽换父级；是否入库、挂到哪个节点由外面的 {@link AttachBar} 统一决定。
     * 每次结构变化整体重建行（节点数在百级内，重建比增量更新更稳）。
     */
    private final class DraftEditor {
        private final List<DraftNode> nodes;
        private final DraftRows rows;
        private final Div summary;
        private AttachBar attach;
        private int baseDepth;
        /** 被点中的行（Ctrl/⌘ 点击多选）：只用来批量改类型，不影响入库范围。 */
        private final Set<String> picked = new LinkedHashSet<>();
        /** 当前渲染出来的行顺序（Shift 连选按这个区间算，和肉眼看到的顺序一致）。 */
        private final List<String> renderedOrder = new ArrayList<>();
        private String lastPickedId;
        private final HorizontalLayout pickBar = new HorizontalLayout();
        private final Span pickLabel = new Span();
        private final ComboBox<DraftType> batchType = new ComboBox<>();
        /** 草稿形状变了（改类型、删行）：外面的入库按钮要跟着调整可用状态。 */
        private Runnable shapeListener;

        DraftEditor(List<DraftNode> nodes, DraftRows rows, Div summary) {
            this.nodes = nodes;
            this.rows = rows;
            this.summary = summary;
            rows.onPick(this::pick);
            buildPickBar();
        }

        /**
         * 批量改类型的工具条：选中行之后才出现。
         * 识别结果常常是「一片都该是显示项」或「一片都该是功能点」，逐行点下拉太慢。
         */
        private void buildPickBar() {
            pickBar.setAlignItems(Alignment.CENTER);
            pickBar.addClassName("draft-pick-bar");
            pickBar.setVisible(false);
            pickLabel.addClassName("muted");
            batchType.setItems(DraftType.ALL);
            batchType.setItemLabelGenerator(DraftType::label);
            batchType.setPlaceholder("批量设为");
            batchType.setWidth("168px");
            batchType.setAllowCustomValue(false);
            Button apply = primary("应用到选中", VaadinIcon.CHECK, () -> {
                DraftType chosen = batchType.getValue();
                if (chosen == null) { Notification.show("先在上面选一个类型"); return; }
                for (DraftNode node : nodes) {
                    if (!picked.contains(node.id)) continue;
                    node.display = chosen.display();
                    node.type = chosen.nodeType();
                }
                render();
            });
            Button selectAll = button("全选", VaadinIcon.CHECK_SQUARE_O, () -> {
                picked.clear();
                nodes.forEach(node -> picked.add(node.id));
                render();
            });
            Button clear = button("取消选择", VaadinIcon.CLOSE_SMALL, () -> {
                picked.clear();
                render();
            });
            pickBar.add(pickLabel, batchType, apply, selectAll, clear);
        }

        /** 批量工具条：放在草稿列表上方，没选中行时整条隐藏。 */
        com.vaadin.flow.component.Component pickToolbar() {
            return pickBar;
        }

        /** 点击一行：普通点击只选它，Ctrl/⌘ 增减选择，Shift 从上次点中的行连选（和左侧功能点树一致）。 */
        private void pick(String id, boolean ctrl, boolean shift) {
            if (id == null) return;
            boolean ranged = false;
            if (shift && lastPickedId != null) {
                int from = renderedOrder.indexOf(lastPickedId);
                int to = renderedOrder.indexOf(id);
                if (from >= 0 && to >= 0) {
                    if (!ctrl) picked.clear();
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) picked.add(renderedOrder.get(i));
                    ranged = true;
                }
            }
            if (!ranged) {
                if (ctrl) {
                    if (!picked.add(id)) picked.remove(id);
                } else {
                    boolean only = picked.size() == 1 && picked.contains(id);
                    picked.clear();
                    if (!only) picked.add(id);
                }
            }
            lastPickedId = id;
            render();
        }

        /** 绑定整批落点：换目录后草稿行整体按新目录的层级重新缩进。 */
        void bind(AttachBar bar) {
            this.attach = bar;
            bar.onChange(this::render);
        }

        /** 注册草稿形状变化的回调，并立刻按当前形状跑一次。 */
        void onShapeChange(Runnable listener) {
            this.shapeListener = listener;
            listener.run();
        }

        void render() {
            rows.removeAll();
            // 行被删掉/重建后，选中集里只保留还在的节点
            picked.retainAll(nodes.stream().map(node -> node.id).toList());
            pickBar.setVisible(!picked.isEmpty());
            pickLabel.setText("已选 " + picked.size() + " 行 · Ctrl/⌘ 点选、Shift 连选");
            TreeNode target = attachTarget();
            baseDepth = target == null ? 0 : depthOfTreeNode(target) + 1;
            if (target != null) rows.add(contextRow(target));
            Map<String, String> parentOf = effectiveDraftParents(nodes,
                    target == null ? null : target.getName(), target == null ? null : target.getNodeType());
            renderedOrder.clear();
            for (DraftNode node : orderDrafts(nodes, parentOf)) {
                renderedOrder.add(node.id);
                rows.add(row(node, baseDepth + depthInDraft(node.id, parentOf)));
            }
            if (lastPickedId != null && !renderedOrder.contains(lastPickedId)) lastPickedId = null;
            // 全是显示项 = 这批只出用例，落点可以直接是功能点；行列改回目录/功能点后自动收回。
            if (attach != null) {
                attach.updateCasesOnly(effectiveCount() == 0 && nodes.stream().anyMatch(node -> node.display));
            }
            updateSummary();
        }

        /** 按当前落点筛完之后真正会建的行数（落点已提供的层级不算）。 */
        int effectiveCount() {
            TreeNode target = attachTarget();
            return effectiveDraftParents(nodes, target == null ? null : target.getName(),
                    target == null ? null : target.getNodeType()).size();
        }

        /** 当前落点；草稿层级要跟它对齐，浅于它的上级目录行是重复的。 */
        private TreeNode attachTarget() {
            return attach == null || attach.rootParentId() == null ? null : findNode(attach.rootParentId());
        }

        /** 落点那一行：只读，缩进到它在树里的层级，说明下面的草稿都挂在这里。 */
        private HorizontalLayout contextRow(TreeNode target) {
            HorizontalLayout line = new HorizontalLayout();
            line.setWidthFull();
            line.setAlignItems(Alignment.CENTER);
            line.addClassName("tree-draft-row");
            line.addClassName("tree-draft-anchor");
            line.getStyle().set("padding-left", (depthOfTreeNode(target) * 16) + "px");
            Span name = new Span(nullSafe(target.getName()));
            name.addClassName("tree-draft-anchor-name");
            Span badge = new Span(typeName(target.getNodeType()) + " · 已存在，作为落点");
            badge.addClassName("draft-element");
            line.add(name, badge);
            line.expand(name);
            line.setFlexGrow(0, badge);
            return line;
        }

        /** 已有节点在树里的深度，用于把草稿行接到它下面。 */
        private int depthOfTreeNode(TreeNode node) {
            int depth = 0;
            Map<String, TreeNode> byId = nodesById();
            TreeNode cursor = node;
            Set<String> guard = new HashSet<>();
            while (cursor != null && cursor.getParentId() != null && guard.add(cursor.getId())) {
                cursor = byId.get(cursor.getParentId());
                depth++;
            }
            return Math.min(depth, 5);
        }

        /** 草稿行相对落点的缩进层级：只数保留下来的祖先。 */
        private static int depthInDraft(String id, Map<String, String> parentOf) {
            int depth = 0;
            String cursor = id;
            Set<String> guard = new HashSet<>();
            while (cursor != null && guard.add(cursor)) {
                String parent = parentOf.get(cursor);
                if (parent == null) break;
                depth++;
                cursor = parent;
            }
            return Math.min(depth, 5);
        }

        private DraftNode find(String id) {
            if (id == null) return null;
            return nodes.stream().filter(n -> id.equals(n.id)).findFirst().orElse(null);
        }

        private HorizontalLayout row(DraftNode node, int depth) {
            HorizontalLayout line = new HorizontalLayout();
            line.setWidthFull();
            line.setAlignItems(Alignment.CENTER);
            line.addClassName("tree-draft-row");
            line.getElement().setAttribute("data-id", node.id);
            if (picked.contains(node.id)) line.addClassName("picked");
            line.getStyle().set("padding-left", (depth * 16) + "px");

            Span handle = new Span("⠿");
            handle.addClassName("draft-handle");
            handle.getElement().setAttribute("title", "按住拖动，可换到别的节点下");

            TextField name = new TextField();
            name.setValue(node.name == null ? "" : node.name);
            name.setWidth("260px");
            name.setPlaceholder("节点名称");
            name.addValueChangeListener(event -> {
                node.name = event.getValue();
                updateSummary();
            });

            ComboBox<DraftType> type = new ComboBox<>();
            type.setItems(DraftType.ALL);
            type.setItemLabelGenerator(DraftType::label);
            type.setValue(DraftType.of(node));
            type.setWidth("168px");
            type.setAllowCustomValue(false);
            type.addValueChangeListener(event -> {
                DraftType picked = event.getValue() == null ? DraftType.POINT : event.getValue();
                node.display = picked.display();
                node.type = picked.nodeType();
                updateSummary();
            });


            Span marker = new Span(node.elementId == null ? "" : "元素");
            marker.addClassName("draft-element");

            Button remove = button("", VaadinIcon.CLOSE_SMALL, () -> {
                removeSubtree(node.id);
                render();
            });
            remove.setWidth("32px");

            line.add(handle, name, type, marker, remove);
            line.expand(name);
            line.setFlexGrow(0, handle, type, marker, remove);

            DragSource<HorizontalLayout> source = DragSource.create(line);
            source.setDragData(node.id);
            DropTarget<HorizontalLayout> target = DropTarget.create(line);
            target.addDropListener(event -> {
                Object data = event.getDragData().orElse(null);
                if (!(data instanceof String draggedId) || draggedId.equals(node.id)) return;
                // 成环判定：被拖节点自己当目标父级时，目标是它的子孙就不能落。
                if (isDescendant(draggedId, node.id)) {
                    Notification.show("不能把节点拖到自己的子孙下面");
                    return;
                }
                DraftNode dragged = find(draggedId);
                if (dragged == null) return;
                dragged.parentId = node.id;
                render();
            });
            return line;
        }

        /** targetId 是否是 nodeId 的子孙：是的话这次拖拽会成环。 */
        private boolean isDescendant(String nodeId, String maybeDescendant) {
            DraftNode current = find(maybeDescendant);
            Set<String> guard = new HashSet<>();
            while (current != null && current.parentId != null && guard.add(current.id)) {
                if (nodeId.equals(current.parentId)) return true;
                current = find(current.parentId);
            }
            return false;
        }

        private void removeSubtree(String id) {
            Set<String> doomed = new HashSet<>();
            doomed.add(id);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (DraftNode node : nodes) {
                    if (node.parentId != null && doomed.contains(node.parentId) && doomed.add(node.id)) changed = true;
                }
            }
            nodes.removeIf(n -> doomed.contains(n.id));
            picked.removeAll(doomed);
        }

        private void updateSummary() {
            if (shapeListener != null) shapeListener.run();
            if (summary == null) return;
            TreeNode target = attachTarget();
            Map<String, String> parentOf = effectiveDraftParents(nodes,
                    target == null ? null : target.getName(), target == null ? null : target.getNodeType());
            StringBuilder sb = new StringBuilder("共 " + parentOf.size() + " 个节点");
            Map<NodeType, Integer> counts = new LinkedHashMap<>();
            for (DraftNode node : nodes) {
                if (!parentOf.containsKey(node.id)) continue;
                counts.merge(node.type == null ? NodeType.POINT : node.type, 1, Integer::sum);
            }
            counts.forEach((type, count) -> sb.append(" · ").append(typeName(type)).append(" ").append(count));
            int displays = (int) nodes.stream().filter(n -> n.display).count();
            if (displays > 0) {
                sb.append(" · 显示项 ").append(displays).append(" 个（不建节点，直接出用例）");
            }
            long redundant = nodes.stream().filter(n -> !n.display && !parentOf.containsKey(n.id)).count();
            if (redundant > 0) {
                sb.append(" · 落点已提供这些层级，略过 ").append(redundant).append(" 个上级/重复节点");
            }
            boolean anyContent = nodes.stream().filter(n -> parentOf.containsKey(n.id))
                    .anyMatch(n -> n.display || (n.type == null ? NodeType.POINT : n.type) == NodeType.POINT);
            if (parentOf.isEmpty() && displays == 0 && !nodes.isEmpty()) {
                sb.append(" · 整批都在落点这一层或之上，没有可新建的内容：换个更靠上的落点");
            } else if (!parentOf.isEmpty() && !anyContent) {
                sb.append(" · 全是目录：要入库请点「入库（含目录）」，只入功能点会跳过");
            }
            summary.setText(sb.toString());
        }
    }

    /** 把预览草稿落库：按 DFS 顺序创建，顶层草稿挂到用户选定的父级；同名同类型直接复用。 */
    private record PersistResult(List<String> pointIds, int created, int reused, int skipped, int omitted, int redundant) {}

    private void saveTreeDraft(List<DraftNode> drafts, String systemId, Dialog dialog, AttachBar attach, boolean onlyPoints) {
        PersistResult result = persistDrafts(drafts, systemId, attach.rootParentId(), onlyPoints);
        dialog.close();
        refreshTree();
        if (result.created() == 0 && result.reused() == 0) {
            // 全是目录时「只入功能点」必然是 0：说清该点哪个按钮，而不是留一个空提示。
            Notification.show(nothingSavedHint(result, onlyPoints));
            return;
        }
        revealUnder(attach.rootParentId());
        saved((onlyPoints ? "已按功能点入库" : "自动建树完成") + "：新建 " + result.created() + " 个节点"
                + (result.reused() > 0 ? "，复用 " + result.reused() + " 个同名节点" : "")
                + (result.omitted() > 0 ? "，忽略 " + result.omitted() + " 个目录节点" : "")
                + (result.skipped() > 0 ? "，跳过 " + result.skipped() + " 个空名称" : ""));
    }

    /** 一个节点都没建/复用时，告诉用户卡在哪一步、下一步该点哪个按钮。 */
    private static String nothingSavedHint(PersistResult result, boolean onlyPoints) {
        if (onlyPoints && result.omitted() > 0) {
            return "这批 " + result.omitted() + " 个节点都是目录，「只入功能点」不建目录："
                    + "点「入库（含目录）」按目录建，或把行的类型改成「功能点」";
        }
        if (result.skipped() > 0) return "没有可入库的节点：有 " + result.skipped() + " 行名称为空，先补上名称";
        if (result.created() == 0 && result.reused() == 0 && result.redundant() > 0) {
            return "这批 " + result.redundant() + " 个节点都在落点这一层或之上（落点已经提供了这几层），"
                    + "没有需要新建的内容：换个更靠上的落点，或把行的类型改深一层再入库";
        }
        return "没有可入库的节点，请检查草稿里的名称与类型";
    }

    /**
     * 按草稿当前形状调整两个入库按钮：没有功能点时「只入功能点」无从入库；
     * 整个草稿都被落点吸收时「入库（含目录）」也建不出东西 —— 都置灰并在悬停里说清原因。
     */
    private static void syncDraftButtons(List<DraftNode> drafts, Button savePoints, Button saveAll, int effectiveCount) {
        boolean hasPoints = drafts.stream()
                .anyMatch(d -> !d.display && (d.type == null || d.type == NodeType.POINT));
        boolean hasNodes = drafts.stream().anyMatch(d -> !d.display);
        boolean onlyDisplay = !hasNodes && drafts.stream().anyMatch(d -> d.display);
        boolean nothingLeft = effectiveCount <= 0;
        String nothingHint = onlyDisplay
                ? "这批全是显示项：它们不建节点，点「生成用例 → 生成台」直接出用例"
                : "整批节点都在落点这一层或之上，没有可新建的内容：换个更靠上的落点";
        savePoints.setEnabled(hasPoints && !nothingLeft);
        savePoints.getElement().setAttribute("title", nothingLeft
                ? nothingHint
                : hasPoints
                        ? "只把草稿里的功能点建进树，目录行与显示项会被跳过"
                        : "这批节点都是目录或显示项，「只入功能点」不会建任何节点：要建目录请点「入库（含目录）」");
        if (saveAll != null) {
            saveAll.setEnabled(hasNodes && !nothingLeft);
            saveAll.getElement().setAttribute("title", nothingLeft || !hasNodes
                    ? nothingHint
                    : "按草稿层级建目录与功能点，挂到上面的落点下；显示项不建节点");
        }
    }

    /** 入库后把落点连同它的上级都展开，左侧树里能直接看到新建出来的节点。 */
    private void revealUnder(String nodeId) {
        String cursor = nodeId;
        Set<String> guard = new HashSet<>();
        while (cursor != null && guard.add(cursor)) {
            collapsedTreeIds.remove(cursor);
            TreeNode node = findNode(cursor);
            cursor = node == null ? null : node.getParentId();
        }
        collapsedTreeIds.remove("ft-root");
        renderTree();
    }

    /**
     * 识别结果 → 生成台：只把功能点落进树（目录节点不建，功能点挂到选定落点），
     * 然后把这些功能点送去生成用例，结果停在右侧生成台等用户确认，不直接写库。
     */
    private void saveAndGenerateDraft(List<DraftNode> drafts, String systemId, Dialog dialog, AttachBar attach,
                                      String regionName, String screenshotId) {
        PersistResult persisted = persistDrafts(drafts, systemId, attach.rootParentId(), true);
        // 显示项不是功能点，也不是节点：用例直接挂到用户选的落点下，不为了挂用例再建一个功能点。
        // 只有落点是 Root（顶层）时才没有可挂的节点，那时才建一个承载用的功能点。
        List<String> displayItems = displayItemNames(drafts);
        String displayOwnerId = null;
        boolean createdOwner = false;
        List<TestCase> displayCases = List.of();
        if (!displayItems.isEmpty()) {
            displayOwnerId = attach.rootParentId() != null
                    ? attach.rootParentId()
                    : ensureDisplayPoint(regionName, systemId, null);
            createdOwner = attach.rootParentId() == null;
            displayCases = caseGenerationService.displayCases(workspace, systemId, displayOwnerId,
                    displayItems, currentUrl(), screenshotId, sessionId);
        }
        refreshTree();
        if (dialog != null) dialog.close();
        if (persisted.pointIds().isEmpty() && displayItems.isEmpty()) {
            Notification.show(nothingSavedHint(persisted, true));
            return;
        }
        revealUnder(attach.rootParentId());
        checkedTreeIds.clear();
        explicitlyUnchecked.clear();
        checkedTreeIds.addAll(persisted.pointIds());
        checkMode = true;
        renderTree();
        updateGenScope();
        if (persisted.pointIds().isEmpty()) {
            // 一批全是展示内容：不用再走 AI（没有元素可测，只会写出一条「页面加载」），
            // 规则已按展示项出好用例，直接进生成台等确认。
            pendingCases = new ArrayList<>(displayCases);
            renderPendingCases("显示项 " + displayItems.size() + " 个",
                    "这批都是展示内容：按「每项一条展示与数据一致性 + 一条整块刷新/异常态」生成，"
                            + (createdOwner
                                    ? "落点是顶层，用例挂到新建的功能点上，确认后入库"
                                    : "用例直接挂在落点「" + parentLabel(displayOwnerId) + "」下，不新增节点"));
            saved("已按 " + displayItems.size() + " 个显示项生成 " + displayCases.size() + " 条用例"
                    + (createdOwner ? "" : "，挂在「" + parentLabel(displayOwnerId) + "」下"));
            return;
        }
        // 混合批次：显示项的用例先攒着，AI 结果回来后再一起进生成台，不能互相覆盖。
        pendingDisplayCases = new ArrayList<>(displayCases);
        saved("已建 " + persisted.created() + " 个功能点"
                + (persisted.reused() > 0 ? "、复用 " + persisted.reused() + " 个同名节点" : "")
                + (displayItems.isEmpty() ? "" : "，另有 " + displayItems.size() + " 个显示项")
                + "，正在生成用例…");
        generateCases();
    }

    /** 草稿里标成「显示项」的行名称（按界面顺序，空名与重复去掉）。 */
    static List<String> displayItemNames(List<DraftNode> drafts) {
        return drafts.stream()
                .filter(draft -> draft.display && draft.name != null && !draft.name.isBlank())
                .map(draft -> draft.name.trim())
                .distinct()
                .toList();
    }

    /**
     * 只有落点是 Root（顶层）时才用得上：显示项的用例总得挂个节点，这时才按识别出来的区域名建一个功能点。
     * 落点是已有节点时用例直接挂那个节点，不再为挂用例多建一个功能点。同名功能点已存在就复用。
     */
    private String ensureDisplayPoint(String regionName, String systemId, String rootParentId) {
        TreeNode root = rootParentId == null ? null : findNode(rootParentId);
        String pointName = displayPointName(regionName, root == null ? null : root.getName());
        TreeNode existing = workspace.nodes.stream()
                .filter(n -> n.getDeletedAt() == null && n.getNodeType() == NodeType.POINT
                        && java.util.Objects.equals(rootParentId, n.getParentId())
                        && pointName.equals(n.getName() == null ? "" : n.getName().trim()))
                .findFirst().orElse(null);
        if (existing != null) return existing.getId();
        TreeNode node = new TreeNode(workspace.projectId, systemId, rootParentId, NodeType.POINT, pointName);
        node.setSortOrder(nextSortOrder(workspace.nodes, rootParentId));
        node.onCreateForStore();
        workspace.nodes.add(node);
        workspace = workspaceService.save(workspace);
        return node.getId();
    }

    /**
     * 承载显示项用例的那个功能点叫什么：用识别出来的区域名；
     * 没有区域名用「界面展示检查」；区域名和落点同名时补「展示检查」，免得出现「Master 下的 Master」。
     */
    static String displayPointName(String regionName, String rootName) {
        String name = regionName == null ? "" : regionName.trim();
        if (name.isEmpty()) return "界面展示检查";
        String anchor = rootName == null ? "" : rootName.trim();
        return !anchor.isEmpty() && anchor.equals(name) ? name + "展示检查" : name;
    }

    /**
     * 草稿落库；返回新建/复用的功能点 id，方便紧接着用它们生成用例。
     *
     * @param rootParentId 整批草稿的落点：草稿里的顶层节点都挂到这个已有节点下，null 表示顶层
     * @param onlyPoints   只建功能点：目录类型的草稿不创建，它下面的功能点直接挂到 rootParentId
     */
    private PersistResult persistDrafts(List<DraftNode> drafts, String systemId, String rootParentId, boolean onlyPoints) {
        TreeNode root = rootParentId == null ? null : findNode(rootParentId);
        DraftPlan plan = planDrafts(workspace, drafts, systemId, rootParentId,
                root == null ? null : root.getName(), root == null ? null : root.getNodeType(), onlyPoints);
        // 没有任何新节点就不写库：只入功能点撞上一批目录时，别白写一次（PostgreSQL 下还会顶掉别人的编辑版本）。
        if (!plan.created().isEmpty()) {
            workspace.nodes.addAll(plan.created());
            workspace = workspaceService.save(workspace);
        }
        return new PersistResult(plan.pointIds(), plan.created().size(), plan.reused(), plan.skipped(),
                plan.omitted(), plan.redundant());
    }

    /** 草稿落库计划：真正要建的节点 + 各类计数；没有任何新节点时上层不写库。 */
    record DraftPlan(List<TreeNode> created, List<String> pointIds, int reused, int skipped, int omitted,
                     int redundant) {}

    /**
     * 纯计算版落库：按 {@link #effectiveDraftParents} 的顺序决定每个草稿建成什么节点、挂在谁下面。
     * 只看快照、不写库，写不写由 {@link #persistDrafts} 决定——这样「只入功能点撞上一批目录」这种分支能被单测盯住。
     */
    static DraftPlan planDrafts(WorkspaceSnapshot snapshot, List<DraftNode> drafts, String systemId,
                                String rootParentId, NodeType targetType, boolean onlyPoints) {
        return planDrafts(snapshot, drafts, systemId, rootParentId, null, targetType, onlyPoints);
    }

    static DraftPlan planDrafts(WorkspaceSnapshot snapshot, List<DraftNode> drafts, String systemId,
                                String rootParentId, String targetName, NodeType targetType, boolean onlyPoints) {
        Map<String, String> parentOf = effectiveDraftParents(drafts, targetName, targetType);
        // pool = 已有节点 + 本批刚建的，同名判断和排序都看在它上面。
        List<TreeNode> pool = new ArrayList<>(snapshot.nodes);
        Map<String, String> idMap = new HashMap<>();
        List<TreeNode> created = new ArrayList<>();
        List<String> pointIds = new ArrayList<>();
        int reused = 0;
        int skipped = 0;
        int omitted = 0;
        for (DraftNode draft : orderDrafts(drafts, parentOf)) {
            String name = draft.name == null ? "" : draft.name.trim();
            if (name.isEmpty()) { skipped++; continue; }
            NodeType type = draft.type == null ? NodeType.POINT : draft.type;
            if (onlyPoints && type != NodeType.POINT) { omitted++; continue; }
            // 父级草稿被忽略（只入功能点 / 与落点重复）或名称不合法时，退回到用户选的落点，而不是掉到顶层。
            String draftParent = parentOf.get(draft.id);
            String parentReal = draftParent == null ? rootParentId : idMap.getOrDefault(draftParent, rootParentId);
            TreeNode existing = findAmong(pool, parentReal, type, name);
            if (existing != null) {
                idMap.put(draft.id, existing.getId());
                if (type == NodeType.POINT) pointIds.add(existing.getId());
                reused++;
                continue;
            }
            TreeNode node = new TreeNode(snapshot.projectId, systemId, parentReal, type, name);
            node.setSortOrder(nextSortOrder(pool, parentReal));
            if (type == NodeType.POINT) node.setElementId(draft.elementId);
            node.onCreateForStore();
            pool.add(node);
            created.add(node);
            idMap.put(draft.id, node.getId());
            if (type == NodeType.POINT) pointIds.add(node.getId());
        }
        // 「略过」只数被落点吸收的目录行；显示项本来就不建节点，不算进来。
        int redundant = (int) drafts.stream()
                .filter(draft -> !draft.display && !parentOf.containsKey(draft.id)).count();
        return new DraftPlan(created, pointIds, reused, skipped, omitted, redundant);
    }

    /** 同一父级下同名同类型已存在就复用；pool 含本批新建的节点，批内同名也不会重复建。 */
    private static TreeNode findAmong(List<TreeNode> pool, String parentId, NodeType type, String name) {
        return pool.stream()
                .filter(n -> n.getDeletedAt() == null && n.getNodeType() == type
                        && java.util.Objects.equals(parentId, n.getParentId())
                        && name.equals(n.getName()))
                .findFirst().orElse(null);
    }

    /** 节点层级：模块 1 → 一级目录 2 → 二级目录 3 → 三级目录 4 → 功能点 5。 */
    private static int levelOf(NodeType type) {
        if (type == null) return 5;
        return switch (type) {
            case MODULE -> 1;
            case TITLE1 -> 2;
            case TITLE2 -> 3;
            case TITLE3 -> 4;
            case POINT -> 5;
        };
    }

    static Map<String, String> effectiveDraftParents(List<DraftNode> drafts, NodeType targetType) {
        return effectiveDraftParents(drafts, null, targetType);
    }

    /**
     * 落点已经提供了它上面那几层，只有「包在内容外面的壳」才算重复，内容本身要留着：
     * <ul>
     *   <li>模块行：永远代表「整个系统」这一层，落点已经有上下文了，跳过；</li>
     *   <li>比落点浅的目录行（落点是一级目录时的模块、落点是二级目录时的模块与一级目录）：跳过；</li>
     *   <li>草稿里就有落点自己那一行（同名同类型）：跳过，它的子孙提上来；</li>
     *   <li><b>与落点同级、更深的目录行一律保留</b> —— 它们才是落点下面要建的内容。</li>
     * </ul>
     * 被跳过的行的子孙往上提，挂到最近一个保留下来的祖先（没有就挂落点）。
     * 返回草稿 id → 有效父级草稿 id（null 表示直接挂到落点），被略过的节点不在 map 里。
     */
    static Map<String, String> effectiveDraftParents(List<DraftNode> drafts, String targetName, NodeType targetType) {
        Map<String, String> parentOf = new LinkedHashMap<>();
        if (targetType == null) {
            for (DraftNode node : drafts) {
                if (node.display) continue;
                parentOf.put(node.id, node.parentId);
            }
            return parentOf;
        }
        int targetLevel = levelOf(targetType);
        String anchorName = targetName == null ? "" : targetName.trim();
        Map<String, DraftNode> byId = new HashMap<>();
        Set<String> redundant = new HashSet<>();
        for (DraftNode node : drafts) {
            byId.put(node.id, node);
            // 显示项不是节点：它只出用例，不参与建树，也不在「略过」的统计里。
            if (node.display) { redundant.add(node.id); continue; }
            NodeType type = node.type == null ? NodeType.POINT : node.type;
            if (type == NodeType.POINT) continue;
            String name = node.name == null ? "" : node.name.trim();
            boolean moduleWrapper = type == NodeType.MODULE;
            boolean shallowerWrapper = levelOf(type) < targetLevel;
            boolean echoesTarget = type == targetType && !anchorName.isEmpty() && anchorName.equals(name);
            if (moduleWrapper || shallowerWrapper || echoesTarget) redundant.add(node.id);
        }
        for (DraftNode node : drafts) {
            if (redundant.contains(node.id)) continue;
            String parent = node.parentId;
            Set<String> guard = new HashSet<>();
            while (parent != null && redundant.contains(parent) && guard.add(parent)) {
                DraftNode up = byId.get(parent);
                parent = up == null ? null : up.parentId;
            }
            parentOf.put(node.id, parent);
        }
        return parentOf;
    }

    /** 按有效父级做 DFS 排序：父行永远排在子行前面，落库也照这个顺序建。 */
    static List<DraftNode> orderDrafts(List<DraftNode> drafts, Map<String, String> parentOf) {
        Map<String, List<DraftNode>> children = new HashMap<>();
        List<DraftNode> roots = new ArrayList<>();
        for (DraftNode node : drafts) {
            if (!parentOf.containsKey(node.id)) continue;
            String parent = parentOf.get(node.id);
            if (parent == null || !parentOf.containsKey(parent)) roots.add(node);
            else children.computeIfAbsent(parent, key -> new ArrayList<>()).add(node);
        }
        List<DraftNode> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DraftNode root : roots) addOrdered(root, children, out, seen);
        for (DraftNode node : drafts) if (parentOf.containsKey(node.id) && seen.add(node.id)) out.add(node);
        return out;
    }

    private static void addOrdered(DraftNode node, Map<String, List<DraftNode>> children,
                                   List<DraftNode> out, Set<String> seen) {
        if (!seen.add(node.id)) return;
        out.add(node);
        for (DraftNode child : children.getOrDefault(node.id, List.of())) addOrdered(child, children, out, seen);
    }

    private void generateCases() {
        if (!requireWorkspace() || systemSelect.getValue() == null) {
            Notification.show("请先选择项目与系统");
            return;
        }
        if (generatingCases) { Notification.show("正在生成，请稍候"); return; }
        List<String> pointIds = generationPointIds();
        if (pointIds.isEmpty()) {
            Notification.show("当前范围内没有功能点，请先在功能树中标记或新增功能点");
            return;
        }
        String screenshotId = null;
        if (autoShot.getValue() && sessionId != null) {
            try {
                screenshotId = workspaceService.captureCurrent(sessionId).getId();
                workspace = workspaceService.getProject(workspace.projectId);
            } catch (RuntimeException ignored) { }
        }
        String projectId = workspace.projectId;
        String systemId = systemSelect.getValue().getId();
        String url = currentUrl();
        int minPerPoint = genMin.getValue() == null ? 3 : Math.max(1, genMin.getValue());
        int maxPerPoint = genMax.getValue() == null ? 8 : Math.max(1, genMax.getValue());
        if (maxPerPoint < minPerPoint) maxPerPoint = minPerPoint;
        CaseGenerationService.Options options = new CaseGenerationService.Options(
                genNegative.getValue(), genAuth.getValue(), minPerPoint, maxPerPoint,
                genExtra.getValue(), screenshotId, url, sessionId);
        generatingCases = true;
        casePreview.removeAll();
        casePreview.add("正在为 " + pointIds.size() + " 个功能点生成用例…");
        UI ui = UI.getCurrent();
        CompletableFuture.supplyAsync(() -> {
            WorkspaceSnapshot snapshot = workspaceService.getProject(projectId);
            return caseGenerationService.generate(snapshot, systemId, pointIds, options);
        }).whenComplete((result, error) -> {
            try {
                log.info("[用例生成] 后台任务结束 error={}", error == null ? "none" : rootMessage(error));
                if (ui == null) return;
                // 注意：不要在 ui.access 之前读 session.getState()，会话未加锁会抛 IllegalStateException。
                ui.access(() -> {
                    generatingCases = false;
                    if (error != null) {
                        pendingDisplayCases = new ArrayList<>();
                        String message = rootMessage(error);
                        casePreview.removeAll();
                        casePreview.add("用例生成失败：" + message);
                        Notification.show("用例生成失败：" + message);
                        return;
                    }
                    pendingCases = new ArrayList<>(result.cases());
                    // 混批里的显示项用例：AI 结果先到，展示项的直接追加在后面，别互相顶掉。
                    pendingCases.addAll(pendingDisplayCases);
                    pendingDisplayCases = new ArrayList<>();
                    log.info("[用例生成] 回推界面 {} 条", pendingCases.size());
                    renderPendingCases(result.summary(), result.note());
                    if (pendingCases.isEmpty()) Notification.show(result.note());
                    else saved((result.aiUsed() ? "AI 已生成 " : "已生成 ") + pendingCases.size() + " 条用例");
                });
            } catch (Throwable e) {
                log.warn("生成结果回推界面失败", e);
            }
        });
    }

    private String currentUrl() {
        if (address.getValue() != null && !address.getValue().isBlank()) return address.getValue().trim();
        TestEnvironment environment = environmentSelect.getValue();
        return environment == null ? "" : environment.getBaseUrl();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private void renderPendingCases(String summary, String note) {
        casePreview.removeAll();
        if (pendingCases.isEmpty()) {
            casePreview.add("没有生成任何用例，请检查功能点与 AI 配置。");
            fitCasesPanel();
            return;
        }
        String tail = note == null ? "" : note.replaceAll("，?共 *[0-9]+ *条", "").trim();
        Div bar = new Div((summary == null ? "" : summary) + " · 本次新生成 " + pendingCases.size() + " 条"
                + (tail.isEmpty() ? "" : " · " + tail));
        bar.addClassName("muted");
        casePreview.add(bar, pendingOutline());
        fitCasesPanel();
    }

    /**
     * 生成结果按功能点树的形态显示：目录/功能点用实线缩进串起来，用例挂在所属功能点下面。
     * 只渲染本次新生成的用例（pendingCases），已入库的用例仍然只在左侧树里看。
     */
    private VerticalLayout pendingOutline() {
        Map<String, TreeNode> byId = nodesById();
        Map<String, List<TestCase>> byPoint = new LinkedHashMap<>();
        List<TestCase> orphans = new ArrayList<>();
        for (TestCase testCase : pendingCases) {
            TreeNode point = testCase.getPointId() == null ? null : byId.get(testCase.getPointId());
            if (point == null) orphans.add(testCase);
            else byPoint.computeIfAbsent(point.getId(), key -> new ArrayList<>()).add(testCase);
        }
        // 只保留「有本次用例的功能点」和它们的祖先，别的节点不出现在大纲里。
        Set<String> visible = new LinkedHashSet<>();
        for (String pointId : byPoint.keySet()) {
            TreeNode cursor = byId.get(pointId);
            Set<String> guard = new HashSet<>();
            while (cursor != null && guard.add(cursor.getId())) {
                visible.add(cursor.getId());
                cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
            }
        }
        Map<String, List<TreeNode>> kids = new HashMap<>();
        List<TreeNode> roots = new ArrayList<>();
        for (String id : visible) {
            TreeNode node = byId.get(id);
            if (node == null) continue;
            if (node.getParentId() == null || !visible.contains(node.getParentId())) roots.add(node);
            else kids.computeIfAbsent(node.getParentId(), key -> new ArrayList<>()).add(node);
        }
        roots.sort(WorkspaceView::compareSiblings);
        kids.values().forEach(list -> list.sort(WorkspaceView::compareSiblings));

        VerticalLayout outline = new VerticalLayout();
        outline.setPadding(false);
        outline.setSpacing(false);
        outline.setWidthFull();
        outline.addClassName("pending-outline");
        for (TreeNode root : roots) appendOutlineNode(outline, root, kids, byPoint);
        if (!orphans.isEmpty()) {
            Div orphanGroup = outlineGroup();
            for (TestCase testCase : orphans) orphanGroup.add(pendingCaseLine(testCase));
            outline.add(orphanGroup);
        }
        return outline;
    }

    /** 缩进组用普通 Div：VerticalLayout 会带上 width:100%，再叠 margin-left 就整段往右溢出被面板裁掉。 */
    private Div outlineGroup() {
        Div group = new Div();
        group.addClassName("pending-outline-children");
        // 缩进靠 margin-left，宽度就减掉同样的 12px；否则每一层都往右多出 12px，最后一列被面板裁掉。
        group.getStyle().set("width", "calc(100% - 12px)");
        return group;
    }

    private void appendOutlineNode(com.vaadin.flow.component.HasComponents container, TreeNode node,
                                   Map<String, List<TreeNode>> kids, Map<String, List<TestCase>> byPoint) {
        List<TestCase> cases = byPoint.getOrDefault(node.getId(), List.of());
        HorizontalLayout row = new HorizontalLayout();
        row.setAlignItems(Alignment.CENTER);
        row.setWidthFull();
        row.addClassName("pending-outline-row");
        row.getElement().setAttribute("data-kind", node.getNodeType() == NodeType.POINT ? "point" : "dir");
        Span name = new Span(nullSafe(node.getName()));
        name.addClassName("pending-outline-name");
        Span tail = new Span(node.getNodeType() == NodeType.POINT ? cases.size() + " 条" : typeName(node.getNodeType()));
        tail.addClassName(node.getNodeType() == NodeType.POINT ? "pending-outline-count" : "pending-outline-type");
        row.add(name, tail);
        row.expand(name);
        row.setFlexGrow(0, tail);
        container.add(row);

        List<TreeNode> children = kids.getOrDefault(node.getId(), List.of());
        if (cases.isEmpty() && children.isEmpty()) return;
        Div group = outlineGroup();
        for (TestCase testCase : cases) group.add(pendingCaseLine(testCase));
        for (TreeNode child : children) appendOutlineNode(group, child, kids, byPoint);
        container.add(group);
    }

    /** 大纲里的一条用例：点标题看详情，行尾 × 从预览里移除（不影响已入库的用例）。 */
    private HorizontalLayout pendingCaseLine(TestCase testCase) {
        HorizontalLayout line = new HorizontalLayout();
        line.setAlignItems(Alignment.CENTER);
        line.setWidthFull();
        line.addClassName("pending-outline-row");
        line.addClassName("pending-outline-case");
        line.getElement().setAttribute("data-kind", "case");

        Anchor title = new Anchor();
        title.addClassName("case-link");
        title.setText((testCase.getCaseNo() == null ? ""
                : com.yuceyang.uitest.testcase.CaseNumbers.normalize(testCase.getCaseNo()) + " ") + testCase.getTitle());
        title.getElement().setAttribute("title", "点击查看用例详情");
        title.getElement().setAttribute("href", "javascript:void(0)");
        title.getElement().addEventListener("click", event -> openCaseEditor(testCase));

        Span meta = new Span(joinText(" · ", categoryLabel(testCase.getCategory()), testCase.getPriority()));
        meta.addClassName("pending-outline-meta");

        Button move = button("", VaadinIcon.SHARE, () -> openCaseMoveDialog(List.of(testCase), true));
        move.addClassName("pending-outline-move");
        move.getElement().setAttribute("title", "改挂载点（只改预览，入库时按新位置写入）");

        Button remove = button("", VaadinIcon.CLOSE_SMALL, () -> {
            pendingCases.remove(testCase);
            renderPendingCases("已更新预览", "");
        });
        remove.addClassName("pending-outline-remove");
        remove.getElement().setAttribute("title", "从预览中移除");

        line.add(title, meta, move, remove);
        line.expand(title);
        line.setFlexGrow(0, meta, move, remove);
        return line;
    }

    /**
     * 用例生成台里的卡片长出来后，把面板高度撑到内容高度并同步整列高度，
     * 这样往下拖分隔条 / 滚右栏就能看到全部用例，而不是被 260px 固定高度裁掉。
     */
    private void fitCasesPanel() {
        if (casePreview.getElement().getNode().getParent() == null) return;
        casePreview.getElement().executeJs("""
                const preview = this;
                const panel = preview.closest('.cases-panel');
                const stack = preview.closest('.right-stack');
                if (!panel || !stack) return;
                panel.style.flex = '0 0 auto';
                panel.style.height = 'auto';
                const grow = () => {
                  const target = Math.max(280, Math.round(panel.scrollHeight + 4));
                  panel.style.height = target + 'px';
                  if (typeof stack.__qtsSync === 'function') stack.__qtsSync();
                };
                requestAnimationFrame(() => requestAnimationFrame(grow));
                """);
    }

    private void confirmCases() {
        if (pendingCases.isEmpty()) { Notification.show("没有待入库的用例，请先点“生成预览”"); return; }
        // 编号只在「没删的用例」里比较，且把已占用的号跳过：
        // 生成时按快照取的号可能已经被别的批次用掉，这里兜一层，避免两个用例共用一个编号。
        Set<String> usedNumbers = new HashSet<>();
        for (TestCase stored : workspace.testCases) {
            if (stored.getDeletedAt() != null) continue;
            String no = com.yuceyang.uitest.testcase.CaseNumbers.normalize(stored.getCaseNo());
            if (!no.isBlank()) usedNumbers.add(no);
        }
        int seq = usedNumbers.stream()
                .mapToInt(com.yuceyang.uitest.testcase.CaseNumbers::sequence)
                .max().orElse(0) + 1;
        int added = 0;
        int skipped = 0;
        for (TestCase testCase : pendingCases) {
            // 同一个功能点下同标题的用例已存在：重复点“确认入库”不再产生第二条，避免树里成对重复。
            boolean duplicate = workspace.testCases.stream().anyMatch(c -> c.getDeletedAt() == null
                    && java.util.Objects.equals(c.getSystemId(), testCase.getSystemId())
                    && java.util.Objects.equals(c.getPointId(), testCase.getPointId())
                    && nullSafe(c.getTitle()).equals(nullSafe(testCase.getTitle())));
            if (duplicate) { skipped++; continue; }
            String number = com.yuceyang.uitest.testcase.CaseNumbers.normalize(testCase.getCaseNo());
            if (number.isBlank() || usedNumbers.contains(number)) {
                while (usedNumbers.contains(com.yuceyang.uitest.testcase.CaseNumbers.format(seq))) seq++;
                number = com.yuceyang.uitest.testcase.CaseNumbers.format(seq);
            }
            testCase.setCaseNo(number);
            usedNumbers.add(number);
            if (workspace.testCases.stream().noneMatch(c -> c.getId().equals(testCase.getId()))) {
                workspace.testCases.add(testCase);
                added++;
            }
        }
        if (added == 0 && skipped > 0) {
            Notification.show("这 " + skipped + " 条用例已入库（同一功能点下标题相同），没有新增");
            return;
        }
        workspace = workspaceService.save(workspace);
        pendingCases = new ArrayList<>();
        refreshTree();
        if (selectedNode != null) renderNodeDetail(selectedNode);
        clearPendingCases();
        saved("已入库 " + added + " 条测试用例" + (skipped > 0 ? "，跳过 " + skipped + " 条重复用例" : ""));
    }

    private String kindName(ElementCandidate element) {
        return switch (element.getKind()) {
            case "button" -> "按钮"; case "input" -> "输入框"; case "select" -> "下拉框";
            case "link" -> "链接"; case "check" -> "勾选"; case "file" -> "上传"; case "chart" -> "图表";
            default -> "其他";
        };
    }

    /** 所有弹框都可拖动：经常要把弹框挪开才能看到后面的页面。 */
    private static Dialog dialog(String title) {
        Dialog dialog = new Dialog(title);
        dialog.setDraggable(true);
        return dialog;
    }

    /**
     * 编号核对：树里 N 条用例、最大编号却不是 N —— 先把编号的家底算清楚，再决定要不要重排。
     * 编号是流水号：删过用例会留空号，历史上两批预览用同一个号入库过会产生重复，
     * 两者都会让「最大编号」和「用例条数」对不上。
     */
    private void openCaseNumberAudit() {
        if (workspace == null) { Notification.show("请先加载项目"); return; }
        List<TestCase> live = workspace.testCases.stream().filter(c -> c.getDeletedAt() == null).toList();
        long deleted = workspace.testCases.size() - live.size();
        Map<String, List<TestCase>> byNo = new LinkedHashMap<>();
        int blank = 0;
        int foreign = 0;
        for (TestCase testCase : live) {
            String no = com.yuceyang.uitest.testcase.CaseNumbers.normalize(testCase.getCaseNo());
            if (no.isBlank()) { blank++; continue; }
            if (com.yuceyang.uitest.testcase.CaseNumbers.sequence(no) <= 0) foreign++;
            byNo.computeIfAbsent(no, key -> new ArrayList<>()).add(testCase);
        }
        List<Map.Entry<String, List<TestCase>>> duplicated = byNo.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Map.Entry.comparingByKey())
                .toList();
        int extraCases = duplicated.stream().mapToInt(entry -> entry.getValue().size() - 1).sum();
        int maxSeq = byNo.keySet().stream()
                .mapToInt(com.yuceyang.uitest.testcase.CaseNumbers::sequence).max().orElse(0);
        int gaps = maxSeq > 0 ? Math.max(0, maxSeq - byNo.size()) : 0;

        Dialog dialog = dialog("用例编号核对");
        dialog.setWidth("640px");
        VerticalLayout body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(false);
        body.add(auditLine("未删除用例", live.size() + " 条（另有已删 " + deleted + " 条，不参与编号）"));
        body.add(auditLine("不同编号", byNo.size() + " 个 · 最大编号 "
                + (maxSeq > 0 ? com.yuceyang.uitest.testcase.CaseNumbers.format(maxSeq) : "无")
                + (gaps > 0 ? " · 缺号 " + gaps + " 个" : "")));
        body.add(auditLine("重复编号", duplicated.isEmpty() ? "没有"
                : duplicated.size() + " 个编号被多条用例共用，多出 " + extraCases + " 条"));
        if (blank > 0 || foreign > 0) {
            body.add(auditLine("无效编号", (blank > 0 ? blank + " 条为空" : "")
                    + (blank > 0 && foreign > 0 ? "，" : "")
                    + (foreign > 0 ? foreign + " 条不是 GN-xxxxx 格式" : "")));
        }
        for (Map.Entry<String, List<TestCase>> entry : duplicated.stream().limit(6).toList()) {
            body.add(auditLine("  " + entry.getKey() + " ×" + entry.getValue().size(),
                    entry.getValue().stream().map(c -> nullSafe(c.getTitle())).limit(3)
                            .collect(java.util.stream.Collectors.joining("；"))
                            + (entry.getValue().size() > 3 ? " …" : "")));
        }
        Span why = new Span("编号是流水号，和条数本来就不一定相等：删过用例会留下空号；"
                + "历史上两批预览用同一个号入库过，会留下重复编号。「按现有编号重排」会压实成 "
                + "GN-00001 ~ " + com.yuceyang.uitest.testcase.CaseNumbers.format(live.size())
                + " 的连续编号（重复的按创建时间先后），编号会变，已导出或已发给别人的编号也会跟着变。");
        why.addClassName("muted");
        why.getElement().getStyle().set("font-size", "12px").set("line-height", "1.6");
        body.add(why);
        dialog.add(body);

        Button renumber = primary("按现有编号重排", VaadinIcon.REFRESH, () -> {
            ConfirmDialog confirm = new ConfirmDialog();
            confirm.setHeader("重排用例编号");
            confirm.setText("会把 " + live.size() + " 条未删除用例压实成 GN-00001 ~ "
                    + com.yuceyang.uitest.testcase.CaseNumbers.format(live.size())
                    + " 的连续编号。已导出或已发给别人的编号也会跟着变，确认继续？");
            confirm.setCancelable(true);
            confirm.setConfirmText("确认重排");
            confirm.addConfirmListener(event -> {
                int count = renumberCaseNumbers();
                dialog.close();
                saved("已重排 " + count + " 条用例编号（GN-00001 ~ "
                        + com.yuceyang.uitest.testcase.CaseNumbers.format(count) + "）");
            });
            confirm.open();
        });
        dialog.getFooter().add(new Button("关闭", event -> dialog.close()), renumber);
        dialog.open();
    }

    /** 把未删除用例按现有编号压实成 GN-00001 起连续编号，返回条数。 */
    private int renumberCaseNumbers() {
        Comparator<TestCase> order = Comparator
                .comparingInt((TestCase c) ->
                        com.yuceyang.uitest.testcase.CaseNumbers.sequence(c.getCaseNo()))
                .thenComparing(c -> c.getCreatedAt() == null ? java.time.Instant.EPOCH : c.getCreatedAt())
                .thenComparing(c -> String.valueOf(c.getId()));
        List<TestCase> ordered = workspace.testCases.stream()
                .filter(c -> c.getDeletedAt() == null)
                .sorted(order)
                .toList();
        int seq = 1;
        for (TestCase testCase : ordered) {
            testCase.setCaseNo(com.yuceyang.uitest.testcase.CaseNumbers.format(seq++));
        }
        workspace = workspaceService.save(workspace);
        refreshTree();
        return ordered.size();
    }

    /** 编号体检结果：条数、重复、空号、无效编号。 */
    private record NumberIssues(int live, int deleted, int distinct, int maxSeq,
                                int duplicated, int extra, int blank, int foreign, int gaps) {
        boolean hasProblem() { return duplicated > 0 || blank > 0 || foreign > 0; }

        String summary() {
            return "未删 " + live + " 条 · 不同编号 " + distinct + " 个 · 最大编号 "
                    + (maxSeq > 0 ? com.yuceyang.uitest.testcase.CaseNumbers.format(maxSeq) : "无")
                    + (gaps > 0 ? " · 缺号 " + gaps + " 个" : "")
                    + (duplicated > 0 ? " · 重复编号 " + duplicated + " 个（多出 " + extra + " 条）" : "")
                    + (blank > 0 ? " · 空编号 " + blank + " 条" : "")
                    + (foreign > 0 ? " · 非 GN 编号 " + foreign + " 条" : "");
        }
    }

    private NumberIssues numberIssues() {
        List<TestCase> live = workspace == null ? List.of() : workspace.testCases.stream()
                .filter(c -> c.getDeletedAt() == null).toList();
        Map<String, Integer> count = new HashMap<>();
        int blank = 0;
        int foreign = 0;
        for (TestCase testCase : live) {
            String no = com.yuceyang.uitest.testcase.CaseNumbers.normalize(testCase.getCaseNo());
            if (no.isBlank()) { blank++; continue; }
            if (com.yuceyang.uitest.testcase.CaseNumbers.sequence(no) <= 0) foreign++;
            count.merge(no, 1, Integer::sum);
        }
        int duplicated = (int) count.values().stream().filter(n -> n > 1).count();
        int extra = count.values().stream().filter(n -> n > 1).mapToInt(n -> n - 1).sum();
        int maxSeq = count.keySet().stream()
                .mapToInt(com.yuceyang.uitest.testcase.CaseNumbers::sequence).max().orElse(0);
        int deleted = workspace == null ? 0 : workspace.testCases.size() - live.size();
        return new NumberIssues(live.size(), deleted, count.size(), maxSeq, duplicated, extra,
                blank, foreign, Math.max(0, maxSeq - count.size()));
    }

    /** 编号核对弹框里的一行：左边说明、右边数值。 */
    private Div auditLine(String label, String value) {
        Div line = new Div();
        Span left = new Span(label + "：");
        left.getStyle().set("color", "#4b5563");
        Span right = new Span(value);
        right.getStyle().set("color", "#1f2937").set("font-weight", "600");
        line.add(left, right);
        return line;
    }

    private void exportCases() {
        if (workspace == null) { Notification.show("请先加载项目"); return; }
        List<TestCase> stored = workspace.testCases.stream()
                .filter(c -> c.getDeletedAt() == null).toList();
        List<TestCase> selected = pointSelectedTreeCases();
        List<TestCase> checkedCases = checkedTreeCases();
        if (stored.isEmpty() && pendingCases.isEmpty() && selected.isEmpty() && checkedCases.isEmpty()) {
            Notification.show("暂无可导出的用例");
            return;
        }

        Dialog dialog = dialog("导出测试用例");
        dialog.setWidth("480px");
        ComboBox<String> format = new ComboBox<>("导出格式");
        format.setItems(CaseExporter.FORMAT_XLSX, CaseExporter.FORMAT_MD, CaseExporter.FORMAT_CSV,
                CaseExporter.FORMAT_JSON, CaseExporter.FORMAT_HTML);
        format.setItemLabelGenerator(code -> switch (code) {
            case CaseExporter.FORMAT_MD -> "Markdown（.md，引用截图地址）";
            case CaseExporter.FORMAT_CSV -> "CSV（.csv，不含截图）";
            case CaseExporter.FORMAT_JSON -> "JSON（.json，不含截图）";
            case CaseExporter.FORMAT_HTML -> "HTML（.html，截图链接）";
            default -> "Excel（.xlsx，嵌入截图）";
        });
        format.setValue(CaseExporter.FORMAT_XLSX);
        format.setWidthFull();

        ComboBox<String> scope = new ComboBox<>("导出范围");
        List<String> scopes = new ArrayList<>();
        scopes.add("stored");
        if (!pendingCases.isEmpty()) scopes.add("pending");
        if (!checkedCases.isEmpty()) scopes.add("checked");
        if (!selected.isEmpty()) scopes.add("selected");
        scope.setItems(scopes);
        scope.setItemLabelGenerator(code -> switch (code) {
            case "pending" -> "用例生成台预览（" + pendingCases.size() + " 条，未入库）";
            case "checked" -> "功能点树里勾选的用例（" + checkedCases.size() + " 条）";
            case "selected" -> "树上选中的用例（" + selected.size() + " 条）";
            default -> "已入库用例（" + stored.size() + " 条）";
        });
        scope.setValue("stored");
        scope.setWidthFull();

        // 编号体检：有重复/空号时直接把问题写在导出弹框里，并允许顺手压实，不用先去别处找按钮。
        NumberIssues issues = numberIssues();
        Span numberNote = new Span("编号：" + issues.summary()
                + (issues.hasProblem() ? "。直接导出会出现「用例 1302 条、编号只到 GN-01237」这种情况。" : "，正常。"));
        numberNote.addClassName("muted");
        numberNote.getElement().getStyle().set("font-size", "12px").set("line-height", "1.5");
        // 编号正常时不占地方：只在真的有重复/空号时提示。
        numberNote.setVisible(issues.hasProblem());
        Checkbox renumber = new Checkbox("导出前重排编号：GN-00001 起连续（会修改库里的用例编号）");
        renumber.setValue(true);
        renumber.setVisible(issues.hasProblem());

        Button cancel = new Button("取消", e -> dialog.close());
        Button export = primary("导出", VaadinIcon.DOWNLOAD, () -> {
            List<TestCase> chosen = switch (String.valueOf(scope.getValue())) {
                case "pending" -> pendingCases;
                case "checked" -> checkedTreeCases();
                case "selected" -> pointSelectedTreeCases();
                default -> stored;
            };
            if (chosen.isEmpty()) { Notification.show("所选范围内没有用例"); return; }
            try {
                if (renumber.isVisible() && renumber.getValue()) {
                    int count = renumberCaseNumbers();
                    // 预览里的用例还没入库，跟着已入库的号段往后排，免得导出时和库里的编号撞车。
                    int seq = count;
                    Comparator<TestCase> pendingOrder = Comparator
                            .comparingInt((TestCase c) ->
                                    com.yuceyang.uitest.testcase.CaseNumbers.sequence(c.getCaseNo()))
                            .thenComparing(c -> c.getCreatedAt() == null ? java.time.Instant.EPOCH : c.getCreatedAt());
                    for (TestCase preview : pendingCases.stream().sorted(pendingOrder).toList()) {
                        preview.setCaseNo(com.yuceyang.uitest.testcase.CaseNumbers.format(++seq));
                    }
                }
                byte[] bytes = caseExporter.render(format.getValue(), exportRows(chosen));
                download(caseExporter.fileName(format.getValue()), bytes, mimeOf(format.getValue()));
                dialog.close();
                Notification.show("已导出 " + chosen.size() + " 条用例（" + format.getValue().toUpperCase() + "）");
            } catch (RuntimeException e) { showError(new StorageException("导出失败：" + e.getMessage())); }
        });
        HorizontalLayout buttons = new HorizontalLayout(cancel, export);
        buttons.setWidthFull();
        buttons.setJustifyContentMode(com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode.END);

        VerticalLayout body = new VerticalLayout(format, scope, numberNote, renumber, buttons);
        body.setPadding(false);
        body.setSpacing(true);
        body.setWidthFull();
        dialog.add(body);
        dialog.open();
    }

    /** 用例 → 导出列（27 列，顺序见 CaseExporter.HEADERS）。 */
    private List<CaseExporter.ExportCase> exportRows(List<TestCase> cases) {
        List<CaseExporter.ExportCase> rows = new ArrayList<>();
        // 导出按用例编号从小到大排：库里存的是写入顺序，直接导会出现 GN-00098 排在 GN-00002 前面。
        // 没有 GN 编号的排最后，同号的按创建时间先后。
        Comparator<TestCase> byCaseNo = Comparator
                .comparingInt((TestCase tc) -> {
                    int seq = com.yuceyang.uitest.testcase.CaseNumbers.sequence(
                            com.yuceyang.uitest.testcase.CaseNumbers.normalize(tc.getCaseNo()));
                    return seq <= 0 ? Integer.MAX_VALUE : seq;
                })
                .thenComparing(tc -> tc.getCreatedAt() == null ? java.time.Instant.EPOCH : tc.getCreatedAt())
                .thenComparing(tc -> String.valueOf(tc.getId()));
        for (TestCase tc : cases.stream().sorted(byCaseNo).toList()) {
            TreePath path = exportPathOf(tc);
            // 层级一律按用例在树里的真实路径取：库里存的 module/l1/l2 是生成当时的快照，
            // 节点改过名、挪过位置，或者早期数据里存成「/」「安全中心-集群管理」这类串，
            // 直接拿去导出就会出现「模块对不上、一级标题串了两级」。
            // 模块：树上没有模块层时用系统名兜底（和生成用例时的写法一致），不写「/」。
            String module = path != null && !"/".equals(path.module()) ? path.module()
                    : firstNonBlank(systemName(tc.getSystemId()), "/");
            String l1 = path != null ? path.l1() : firstNonBlank(tc.getL1(), "/");
            String l2 = path != null ? path.l2() : firstNonBlank(tc.getL2(), "/");
            String point = path != null ? path.point() : "/";
            String shotRel = shotLabel(tc.getScreenshotId());
            String shotPath = shotRel.isBlank() ? "" : "screenshots/" + shotRel;
            rows.add(new CaseExporter.ExportCase(
                    com.yuceyang.uitest.testcase.CaseNumbers.normalize(tc.getCaseNo()),
                    projectName(),
                    systemName(tc.getSystemId()),
                    module, l1, l2, point,
                    nullSafe(tc.getTitle()),
                    nullSafe(tc.getDesignRequirement()),
                    nullSafe(tc.getTestTrack()),
                    "验证通过且无新增报错即结束",
                    nullSafe(tc.getPreconditions()),
                    nullSafe(tc.getSteps()),
                    nullSafe(tc.getExpectedResult()),
                    "", "",
                    nullSafe(tc.getActualResult()),
                    "", "", "",
                    remarkOf(tc),
                    tc.getMethod() == null || tc.getMethod().isBlank() ? "GET" : tc.getMethod(),
                    nullSafe(tc.getUrl()),
                    nullSafe(tc.getParamsText()),
                    caseStatusName(tc.getStatus()),
                    shotPath,
                    shotRel.isBlank() ? null : screenshotFileStore.resolve(shotRel)));
        }
        return rows;
    }

    /** 导出用的层级：模块 / 一级 / 二级（含三级）/ 功能点；缺的那层用「/」。 */
    private record TreePath(String module, String l1, String l2, String point) {}

    /**
     * 按用例挂载点在树里的真实路径取层级；节点找不到（未绑定、已删）时返回 null，
     * 由调用方回落到用例里存的字段。
     */
    private TreePath exportPathOf(TestCase tc) {
        if (workspace == null || tc.getPointId() == null) return null;
        Map<String, TreeNode> byId = nodesById();
        TreeNode node = workspace.nodes.stream()
                .filter(n -> tc.getPointId().equals(n.getId()) && n.getDeletedAt() == null)
                .findFirst().orElse(null);
        if (node == null) return null;
        List<TreeNode> chain = new ArrayList<>();
        TreeNode cursor = node;
        Set<String> guard = new HashSet<>();
        while (cursor != null && guard.add(cursor.getId())) {
            chain.add(cursor);
            cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
        }
        String module = null;
        List<String> dirs = new ArrayList<>();
        for (TreeNode item : chain.reversed()) {
            String name = nullSafe(item.getName()).trim();
            if (name.isEmpty() || "/".equals(name)) continue;
            if (item.getNodeType() == NodeType.MODULE) module = name;
            else if (item.getNodeType() == NodeType.TITLE1 || item.getNodeType() == NodeType.TITLE2
                    || item.getNodeType() == NodeType.TITLE3) dirs.add(name);
        }
        String l1 = dirs.isEmpty() ? "/" : dirs.getFirst();
        // 有三级标题时并进「二级标题」一列（和生成用例时的写法一致），不额外占列。
        String l2 = dirs.size() >= 2 ? String.join("-", dirs.subList(1, dirs.size())) : "/";
        String point = node.getNodeType() == NodeType.POINT ? nullSafe(node.getName()).trim() : "/";
        return new TreePath(module == null || module.isBlank() ? "/" : module, l1, l2,
                point.isEmpty() ? "/" : point);
    }

    /** 备注为空时用「优先级;分类」兜底，与 Python 导出一致。 */
    private String remarkOf(TestCase tc) {
        if (tc.getRemark() != null && !tc.getRemark().isBlank()) return tc.getRemark();
        String priority = tc.getPriority() == null || tc.getPriority().isBlank() ? "P2" : tc.getPriority().trim();
        String code = priority.length() > 1 ? priority.substring(priority.length() - 1) : "2";
        String category = tc.getCategory() == null || tc.getCategory().isBlank()
                ? "positive" : categoryLabel(tc.getCategory());
        return "P" + code + ";" + category;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String mimeOf(String format) {
        return switch (format) {
            case CaseExporter.FORMAT_MD -> "text/markdown;charset=UTF-8";
            case CaseExporter.FORMAT_CSV -> "text/csv;charset=UTF-8";
            case CaseExporter.FORMAT_JSON -> "application/json;charset=UTF-8";
            case CaseExporter.FORMAT_HTML -> "text/html;charset=UTF-8";
            default -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
    }

    /**
     * 浏览器端直接落盘：把字节转 base64 交给页面生成 Blob 再触发下载。
     * 比 Anchor + StreamResource/DownloadHandler 稳——那两条路都要等服务端注册资源地址，
     * 隐藏锚点在挂载瞬间点不到 href（实测拿到了空 href 的 a 标签，文件不会下载）。
     */
    private void download(String fileName, byte[] bytes, String mime) {
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        UI.getCurrent().getPage().executeJs("""
                const raw = atob($0);
                const buf = new Uint8Array(raw.length);
                for (let i = 0; i < raw.length; i++) buf[i] = raw.charCodeAt(i);
                const blob = new Blob([buf], {type: $1});
                const a = document.createElement('a');
                a.href = URL.createObjectURL(blob);
                a.download = $2;
                a.style.display = 'none';
                document.body.appendChild(a);
                a.click();
                setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 5000);
                """, base64, mime, fileName);
    }

    private List<String> nodePath(String pointId) {
        List<String> path = new ArrayList<>();
        TreeNode current = workspace.nodes.stream().filter(n -> n.getId().equals(pointId)).findFirst().orElse(null);
        while (current != null) {
            path.addFirst(current.getName());
            String parentId = current.getParentId();
            current = parentId == null ? null : workspace.nodes.stream().filter(n -> n.getId().equals(parentId)).findFirst().orElse(null);
        }
        return path;
    }

    /** 当前项目名称（导出时单独占一列）。 */
    private String projectName() {
        if (workspace == null || workspace.getProject() == null) return "";
        return nullSafe(workspace.getProject().getName());
    }

    private String systemName(String systemId) {
        return workspace.systems.stream().filter(s -> s.getId().equals(systemId)).findFirst()
                .map(SystemUnderTest::getName).orElse("");
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }

    private void openProjectDialog() {
        Dialog dialog = dialog("新建项目");
        dialog.setWidth("520px");
        TextField name = requiredField("项目名称");
        TextArea description = new TextArea("项目说明");
        description.setWidthFull();
        description.setMinHeight("96px");
        dialog.add(formBody(name, description));
        footer(dialog, "创建", () -> {
            workspaceService.createProject(name.getValue(), description.getValue());
            dialog.close(); refreshProjects();
            projectSelect.getListDataView().getItems().filter(p -> p.name().equals(name.getValue())).findFirst().ifPresent(projectSelect::setValue);
            saved("项目已保存");
        });
        dialog.open();
    }

    private void openSystemDialog() {
        if (!requireWorkspace()) return;
        Dialog dialog = dialog("添加被测系统");
        dialog.setWidth("560px");
        TextField name = requiredField("系统名称");
        TextField code = requiredField("系统编码");
        TextField environment = requiredField("默认环境名称"); environment.setValue("测试环境");
        TextField url = new TextField("环境地址"); url.setPlaceholder("http://localhost:8081");
        dialog.add(formBody(name, code, environment, url));
        footer(dialog, "保存", () -> {
            workspace = workspaceService.addSystem(workspace.projectId, name.getValue(), code.getValue(), environment.getValue(), url.getValue());
            systemSelect.setItems(workspace.systems);
            workspace.systems.stream().filter(s -> s.getName().equals(name.getValue())).findFirst().ifPresent(systemSelect::setValue);
            dialog.close(); saved("系统与环境已保存"); refreshProjectsKeepSelection();
        });
        dialog.open();
    }

    private void openEnvironmentDialog() {
        if (systemSelect.getValue() == null) { Notification.show("请先选择被测系统"); return; }
        Notification.show("环境管理将接入独立配置页");
    }

    private void openNodeDialog() { openNodeDialog(null); }

    /** 当前系统下未删除的节点，按 id 索引（层级标签与父级校验共用）。 */
    private Map<String, TreeNode> nodesById() {
        Map<String, TreeNode> byId = new LinkedHashMap<>();
        if (workspace == null) return byId;
        String systemId = systemSelect.getValue() == null ? null : systemSelect.getValue().getId();
        for (TreeNode node : workspace.nodes) {
            if (node.getDeletedAt() != null) continue;
            if (systemId != null && !systemId.equals(node.getSystemId())) continue;
            byId.put(node.getId(), node);
        }
        return byId;
    }

    /** 父级下拉候选：按树的层级顺序展开（父在前、同级按排序号），并排除自己与自己的子孙。 */
    private List<TreeNode> orderedNodeChoices(TreeNode existing) {
        String systemId = systemSelect.getValue() == null ? null : systemSelect.getValue().getId();
        Map<String, TreeNode> byId = new LinkedHashMap<>();
        for (TreeNode node : workspace.nodes) {
            if (!java.util.Objects.equals(systemId, node.getSystemId()) || node.getDeletedAt() != null) continue;
            byId.put(node.getId(), node);
        }
        Map<String, List<TreeNode>> kids = new HashMap<>();
        List<TreeNode> roots = new ArrayList<>();
        for (TreeNode node : byId.values()) {
            if (node.getParentId() == null || !byId.containsKey(node.getParentId())) roots.add(node);
            else kids.computeIfAbsent(node.getParentId(), key -> new ArrayList<>()).add(node);
        }
        roots.sort(WorkspaceView::compareSiblings);
        kids.values().forEach(list -> list.sort(WorkspaceView::compareSiblings));
        List<TreeNode> out = new ArrayList<>();
        for (TreeNode root : roots) collectChoices(root, kids, existing, out);
        return out;
    }

    private void collectChoices(TreeNode node, Map<String, List<TreeNode>> kids, TreeNode existing, List<TreeNode> out) {
        if (existing != null && (existing.getId().equals(node.getId()) || isDescendant(existing.getId(), node.getId()))) {
            return;
        }
        out.add(node);
        for (TreeNode child : kids.getOrDefault(node.getId(), List.of())) {
            collectChoices(child, kids, existing, out);
        }
    }

    /** 层级前缀：每深一层加一条实线竖线，再用分支符号接名称，和左侧功能点树的缩进一致。 */
    private String parentLabel(TreeNode node, Map<String, TreeNode> byId) {
        int depth = 0;
        TreeNode cursor = node.getParentId() == null ? null : byId.get(node.getParentId());
        while (cursor != null && depth < 20) {
            depth++;
            cursor = cursor.getParentId() == null ? null : byId.get(cursor.getParentId());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("│ ");
        if (depth > 0) sb.append("├ ");
        String name = node.getName() == null ? "" : node.getName();
        return sb.append(name).toString();
    }

    private void openNodeDialog(TreeNode existing) { openNodeDialog(existing, null); }

    private void openNodeDialog(TreeNode existing, TreeNode presetParent) {
        if (systemSelect.getValue() == null) { Notification.show("请先选择被测系统"); return; }
        Dialog dialog = dialog(existing == null ? "新增树节点" : "编辑树节点");
        dialog.setWidth("520px");
        TextField name = requiredField("名称"); if (existing != null) name.setValue(existing.getName());
        ComboBox<NodeType> type = new ComboBox<>("类型");
        type.setItems(NodeType.values()); type.setItemLabelGenerator(this::typeName);
        type.setValue(existing == null ? NodeType.MODULE : existing.getNodeType());
        ComboBox<TreeNode> parent = new ComboBox<>("父级目录");
        // 下拉按功能点树的层级顺序排列，并用竖线+分支标出层级缩进，和左侧树保持一致。
        // 功能点下只能挂用例，所以功能点不出现在父级候选里（历史数据里当前父级是功能点的，仍保留那一项以便显示，并标注建议改掉）。
        String currentParentId = existing == null ? null : existing.getParentId();
        List<TreeNode> choices = orderedNodeChoices(existing).stream()
                .filter(n -> n.getNodeType() != NodeType.POINT
                        || (currentParentId != null && currentParentId.equals(n.getId())))
                .toList();
        Map<String, TreeNode> allById = nodesById();
        parent.setItems(choices);
        parent.setItemLabelGenerator(node -> parentLabel(node, allById)
                + (node.getNodeType() == NodeType.POINT ? "（功能点，不可作父级，建议改掉）" : ""));
        parent.setPlaceholder("不选则为顶层");
        parent.setClearButtonVisible(true);
        parent.setWidthFull();
        if (existing != null) {
            parent.setValue(choices.stream().filter(n -> n.getId().equals(existing.getParentId())).findFirst().orElse(null));
        } else if (presetParent != null) {
            parent.setValue(choices.stream().filter(n -> n.getId().equals(presetParent.getId())).findFirst().orElse(null));
        }
        Span ruleTip = new Span("功能点可以挂在 Root、模块或任意一级/二级/三级标题下，但不能挂在功能点下面——功能点下只放用例。");
        ruleTip.addClassName("muted");
        dialog.add(formBody(name, type, parent, ruleTip));
        footer(dialog, "保存", () -> {
            String parentId = parent.getValue() == null ? null : parent.getValue().getId();
            String nodeName = name.getValue() == null ? "" : name.getValue().trim();
            if (nodeName.isBlank()) throw new StorageException("节点名称不能为空");
            boolean duplicate = workspace.nodes.stream()
                    .filter(n -> !n.getId().equals(existing == null ? "" : existing.getId()))
                    .filter(n -> systemSelect.getValue().getId().equals(n.getSystemId()))
                    .anyMatch(n -> java.util.Objects.equals(parentId, n.getParentId()) && nodeName.equals(n.getName()));
            if (duplicate) throw new StorageException("同级目录下已存在同名节点");
            // 层级规则：功能点不能挂在功能点下面（功能点下只放用例），功能点自己也不能带着子节点变成功能点。
            TreeNode parentNode = parentId == null ? null : findNode(parentId);
            if (parentNode != null && parentNode.getNodeType() == NodeType.POINT) {
                throw new StorageException("父级「" + parentNode.getName() + "」是功能点：功能点下只能挂用例，请改选模块/标题或顶层");
            }
            if (type.getValue() == NodeType.POINT && existing != null) {
                List<TreeNode> kids = workspace.nodes.stream()
                        .filter(n -> n.getDeletedAt() == null && existing.getId().equals(n.getParentId()))
                        .toList();
                if (!kids.isEmpty()) {
                    throw new StorageException("该节点下还有 " + kids.size()
                            + " 个子节点，不能改成功能点——先把子节点移走（功能点下只能挂用例）");
                }
            }
            TreeNode node = existing == null ? new TreeNode(workspace.projectId, systemSelect.getValue().getId(), null, type.getValue(), nodeName) : existing;
            if (existing == null) node.setSortOrder(nextSortOrder(parentId));
            node.setName(nodeName); node.setNodeType(type.getValue()); node.setParentId(parentId);
            if (existing == null) { node.onCreateForStore(); workspace.nodes.add(node); }
            workspace = workspaceService.save(workspace);
            dialog.close(); refreshTree(); saved("功能树已保存");
        });
        if (existing != null) {
            Button delete = new Button("删除节点", VaadinIcon.TRASH.create(), event -> {
                boolean hasChildren = workspace.nodes.stream().anyMatch(n -> existing.getId().equals(n.getParentId()) && n.getDeletedAt() == null);
                boolean hasCases = workspace.testCases.stream().anyMatch(c -> existing.getId().equals(c.getPointId()) && c.getDeletedAt() == null);
                if (hasChildren || hasCases) {
                    Notification.show("该节点下仍有子节点或用例，请先移动或删除后再删节点");
                    return;
                }
                ConfirmDialog confirm = new ConfirmDialog();
                confirm.setHeader("删除节点");
                confirm.setText("确定删除“" + existing.getName() + "”？删除后不会影响其它节点，但不可直接恢复。");
                confirm.setCancelable(true);
                confirm.setConfirmText("确认删除");
                confirm.setConfirmButtonTheme("error primary");
                confirm.addConfirmListener(e -> {
                    existing.setDeletedAt(java.time.Instant.now());
                    workspace = workspaceService.save(workspace);
                    dialog.close(); refreshTree(); saved("节点已删除");
                });
                confirm.open();
            });
            delete.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            dialog.getFooter().add(delete);
        }
        dialog.open();
    }

    private void toggleTree() {
        collapsed = !collapsed;
        if (collapsed) {
            collapsedTreeIds.add("ft-root");
            workspace.nodes.stream().filter(n -> n.getDeletedAt() == null).forEach(n -> collapsedTreeIds.add(n.getId()));
        } else {
            collapsedTreeIds.clear();
        }
        renderTree();
    }

    private void navigate() {
        if (systemSelect.getValue() == null) { Notification.show("请先选择被测系统"); return; }
        TestEnvironment environment = environmentSelect.getValue();
        if (environment == null) {
            environment = workspace.environments.stream()
                    .filter(e -> systemSelect.getValue().getId().equals(e.getSystemId()))
                    .findFirst().orElse(null);
            if (environment == null) { Notification.show("请先为被测系统配置测试环境"); return; }
            environmentSelect.setValue(environment);
        }
        if (address.getValue() == null || address.getValue().isBlank()) {
            address.setValue(environment.getBaseUrl());
        }
        browserStatus.setText("连接中");
        try {
            String systemId = systemSelect.getValue().getId();
            TestEnvironment targetEnvironment = environment;
            if (sessionId != null && (!systemId.equals(sessionSystemId)
                    || !targetEnvironment.getId().equals(sessionEnvironmentId))) {
                // 切换了系统/环境，旧会话属于另一套系统，不能复用，否则发现结果会挂错系统。
                workspaceService.closeBrowser(sessionId);
                sessionId = null;
            }
            if (sessionId != null) {
                // 复用同一个浏览器上下文：保留登录态，也省掉重复启动浏览器的几秒。
                address.setValue(workspaceService.navigate(sessionId, address.getValue()));
                browserStatus.setText("已启动");
                refreshMirror();
                return;
            }
            var session = workspaceService.openBrowser(systemId, targetEnvironment.getId(), address.getValue());
            sessionId = session.id();
            sessionSystemId = systemId;
            sessionEnvironmentId = targetEnvironment.getId();
            zoomPercent = 100;
            browserStatus.setText("已启动");
            showLandedUrl();
            refreshMirror();
        } catch (RuntimeException e) {
            if (isBrowserClosed(e)) {
                sessionId = null;
                zoomPercent = 100;
                browserStatus.setText("未启动");
            } else {
                browserStatus.setText("启动失败");
            }
            showError(e);
        }
    }

    /** 打开后把地址栏换成真实落点（登录会重定向），下次重启也按这个地址回填。 */
    private void showLandedUrl() {
        if (sessionId == null) return;
        try {
            String landed = workspaceService.currentUrl(sessionId);
            if (landed != null && !landed.isBlank()) address.setValue(landed);
        } catch (RuntimeException ignored) {
            // 拿不到地址不影响浏览，保留地址栏原值
        }
    }

    private void refreshMirror() {
        if (sessionId == null) return;
        var mirror = workspaceService.mirror(sessionId);
        if (mirrorWrap.getParent().isEmpty()) {
            browserImage.addClassName("browser-image");
            // 图片默认可拖拽，会触发 HTML5 drag 并取消 pointer 事件，导致框选永远收不到 pointerup。
            browserImage.getElement().setAttribute("draggable", "false");
            mirrorWrap.addClassName("mirror-wrap");
            mirrorWrap.removeAll();
            mirrorOverlay.removeAll();
            mirrorOverlay.addClassName("mirror-overlay");
            mirrorWrap.add(browserImage, mirrorOverlay);
            browserCanvas.removeAll();
            browserCanvas.add(mirrorWrap, mirrorInput);
            installMirrorInput();
        }
        mirrorWidth = Math.max(1, mirror.width());
        mirrorHeight = Math.max(1, mirror.height());
        browserImage.setSrc(mirror.image());
        browserImage.setAlt("实时页面");
        applyMirrorDragMode();
        drawOverlay();
    }

    /** 隐藏输入代理 + 镜像上的点击/框选/滚轮事件。 */
    private void installMirrorInput() {
        mirrorWrap.getElement().executeJs("""
                const wrap = this;
                if (wrap.__qtsBound) return;
                wrap.__qtsBound = true;
                const img = wrap.querySelector('img');
                const ime = document.getElementById('qts-ime');
                const focusIme = () => { if (ime) { ime.value = ''; ime.focus(); } };
                const pagePoint = (e) => {
                    const r = img.getBoundingClientRect();
                    const sx = (img.naturalWidth || 1) / r.width;
                    const sy = (img.naturalHeight || 1) / r.height;
                    return [(e.clientX - r.left) * sx, (e.clientY - r.top) * sy];
                };
                const wrapPoint = (e) => {
                    const r = wrap.getBoundingClientRect();
                    return [e.clientX - r.left, e.clientY - r.top];
                };

                let drag = null, lastMoveAt = 0;
                // 「拖拽交互」开：左键按下/移动/松开原样转发给页面，处理滑块验证码、拖拽排序这类控件；
                // 关：保持原来的框选元素。
                const rawMode = () => wrap.classList.contains('mirror-drag-mode');
                wrap.addEventListener('pointerdown', (e) => {
                    // 取消默认的 mousedown 聚焦：否则刚聚焦隐藏输入框，浏览器又把焦点收回到 body，
                    // 后续键入/粘贴全部落到工作台页面而不是目标页面。
                    e.preventDefault();
                    focusIme();
                    if (e.button !== 0) return;
                    const [wx, wy] = wrapPoint(e);
                    const raw = rawMode();
                    drag = { id: e.pointerId, sx: e.clientX, sy: e.clientY, wx, wy,
                             additive: e.shiftKey, moved: false, raw };
                    try { wrap.setPointerCapture(e.pointerId); } catch (ignored) {}
                    if (raw) {
                        const [px, py] = pagePoint(e);
                        img.$server.mirrorMouse('down', px, py);
                    }
                });
                wrap.addEventListener('pointermove', (e) => {
                    if (!drag || drag.id !== e.pointerId) return;
                    if (drag.raw) {
                        const now = Date.now();
                        if (now - lastMoveAt < 16) return;
                        lastMoveAt = now;
                        const [px, py] = pagePoint(e);
                        img.$server.mirrorMouse('move', px, py);
                        return;
                    }
                    if (!drag.moved && Math.hypot(e.clientX - drag.sx, e.clientY - drag.sy) < 5) return;
                    drag.moved = true;
                    const [wx, wy] = wrapPoint(e);
                    let box = wrap.querySelector('.mirror-marquee');
                    if (!box) { box = document.createElement('div'); box.className = 'mirror-marquee'; wrap.appendChild(box); }
                    box.style.left = Math.min(drag.wx, wx) + 'px';
                    box.style.top = Math.min(drag.wy, wy) + 'px';
                    box.style.width = Math.abs(wx - drag.wx) + 'px';
                    box.style.height = Math.abs(wy - drag.wy) + 'px';
                });
                wrap.addEventListener('pointerup', (e) => {
                    if (!drag || drag.id !== e.pointerId) return;
                    if (drag.raw) {
                        drag = null;
                        const [px, py] = pagePoint(e);
                        img.$server.mirrorMouse('up', px, py);
                        return;
                    }
                    const wasDrag = drag.moved, additive = drag.additive;
                    drag = null;
                    wrap.querySelector('.mirror-marquee')?.remove();
                    if (wasDrag) {
                        const r = img.getBoundingClientRect();
                        const sx = (img.naturalWidth || 1) / r.width;
                        const sy = (img.naturalHeight || 1) / r.height;
                        img.$server.mirrorMarquee(dragStartPageX, dragStartPageY,
                            (e.clientX - r.left) * sx, (e.clientY - r.top) * sy, additive);
                        return;
                    }
                    const [x, y] = pagePoint(e);
                    const box = e.target.closest?.('.mirror-box');
                    if ((e.ctrlKey || e.metaKey) && box) {
                        img.$server.mirrorToggleBox(box.dataset.id);
                        return;
                    }
                    img.$server.mirrorClick(x, y, 0, 1);
                });
                wrap.addEventListener('pointercancel', (e) => {
                    if (!drag || !drag.raw) return;
                    drag = null;
                    const [px, py] = pagePoint(e);
                    img.$server.mirrorMouse('up', px, py);
                });
                let dragStartPageX = 0, dragStartPageY = 0;
                wrap.addEventListener('pointerdown', (e) => {
                    if (e.button !== 0) return;
                    const r = img.getBoundingClientRect();
                    const sx = (img.naturalWidth || 1) / r.width;
                    const sy = (img.naturalHeight || 1) / r.height;
                    dragStartPageX = (e.clientX - r.left) * sx;
                    dragStartPageY = (e.clientY - r.top) * sy;
                }, true);
                wrap.addEventListener('contextmenu', (e) => {
                    e.preventDefault();
                    focusIme();
                    const [x, y] = pagePoint(e);
                    img.$server.mirrorClick(x, y, 2, 1);
                });
                wrap.addEventListener('wheel', (e) => {
                    e.preventDefault();
                    img.$server.mirrorWheel(e.deltaX, e.deltaY);
                }, { passive: false });

                if (ime && !ime.__qtsBound) {
                    ime.__qtsBound = true;
                    let composing = false;
                    const sync = () => { clearTimeout(ime.__syncTimer); ime.__syncTimer = setTimeout(() => ime.$server.mirrorSync(), 260); };
                    ime.addEventListener('compositionstart', () => { composing = true; });
                    ime.addEventListener('compositionend', (e) => {
                        composing = false;
                        const t = e.data || ime.value || '';
                        ime.value = '';
                        if (t) ime.$server.mirrorInsert(t);
                        sync();
                    });
                    ime.addEventListener('input', (e) => {
                        if (composing || e.isComposing) return;
                        const v = ime.value;
                        if (!v) return;
                        ime.value = '';
                        ime.$server.mirrorInsert(v);
                        sync();
                    });
                    ime.addEventListener('keydown', (e) => {
                        if (composing || e.isComposing || e.keyCode === 229) return;
                        const special = { Enter: 'Enter', Tab: 'Tab', Backspace: 'Backspace', Escape: 'Escape',
                            ArrowUp: 'ArrowUp', ArrowDown: 'ArrowDown', ArrowLeft: 'ArrowLeft', ArrowRight: 'ArrowRight', Delete: 'Delete' };
                        if (special[e.key]) {
                            e.preventDefault();
                            ime.value = '';
                            ime.$server.mirrorPress(special[e.key]);
                            sync();
                            return;
                        }
                        if (e.ctrlKey || e.metaKey) {
                            const k = (e.key || '').toLowerCase();
                            if (k === 'c') {
                                e.preventDefault();
                                ime.$server.mirrorCopy().then((text) => {
                                    if (text) {
                                        const ta = document.createElement('textarea');
                                        ta.value = text;
                                        ta.style.position = 'fixed'; ta.style.opacity = '0';
                                        document.body.appendChild(ta); ta.select();
                                        try { document.execCommand('copy'); } catch (ignored) {}
                                        ta.remove();
                                        // 复制用的临时 textarea 会抢走焦点，复制完把焦点还给隐藏输入框。
                                        focusIme();
                                    }
                                });
                                return;
                            }
                            if (k === 'a' || k === 'x') {
                                e.preventDefault();
                                ime.$server.mirrorPress('Control+' + k);
                                sync();
                            }
                        }
                    });
                    ime.addEventListener('paste', (e) => {
                        e.preventDefault();
                        const t = (e.clipboardData || window.clipboardData).getData('text');
                        if (t) { ime.$server.mirrorInsert(t); sync(); }
                    });
                }
                """);
    }

    /**
     * 镜像叠框由服务端直接生成 DOM，不依赖前端 executeJs 传参：
     * 位置按截图像素换算成百分比，镜像缩放到任意宽度都能对上。
     */
    private void drawOverlay() {
        if (sessionId == null) return;
        mirrorOverlay.removeAll();
        for (ElementCandidate element : discoveredElements) {
            if (element.getWidth() < 2 || element.getHeight() < 2) continue;
            Div box = new Div();
            box.addClassName("mirror-box");
            if (element.isConfirmed()) box.addClassName("marked");
            if (selectedElementIds.contains(element.getId())) box.addClassName("selected");
            box.getStyle().set("left", percent(element.getX(), mirrorWidth));
            box.getStyle().set("top", percent(element.getY(), mirrorHeight));
            box.getStyle().set("width", percent(element.getWidth(), mirrorWidth));
            box.getStyle().set("height", percent(element.getHeight(), mirrorHeight));
            box.getElement().setAttribute("title", element.getName());
            box.getElement().setAttribute("data-id", element.getId());
            mirrorOverlay.add(box);
        }
    }

    private static String percent(double value, int total) {
        double ratio = Math.max(0, Math.min(1, value / Math.max(1, total))) * 100;
        return String.format(java.util.Locale.ROOT, "%.3f%%", ratio);
    }

    /**
     * 框选按「最小元素」命中：只选了区域内的小控件，不会因为擦到边就把整个弹框/整页容器一起选上。
     * 判定规则：元素中心落在框内，或者框内覆盖到该元素 60% 以上；明显大于选框的外层容器一律排除。
     */
    private void selectByMarquee(double x1, double y1, double x2, double y2, boolean additive) {
        if (!additive) selectedElementIds.clear();
        double left = Math.min(x1, x2), right = Math.max(x1, x2);
        double top = Math.min(y1, y2), bottom = Math.max(y1, y2);
        double boxWidth = Math.max(1, right - left), boxHeight = Math.max(1, bottom - top);
        for (ElementCandidate element : discoveredElements) {
            if (element.getWidth() < 2 || element.getHeight() < 2) continue;
            double ex = element.getX(), ey = element.getY();
            double ew = element.getWidth(), eh = element.getHeight();
            // 比选框大出一截的容器（弹窗外壳、整页布局）不是用户想选的目标。
            if (ew > boxWidth * 1.8 && eh > boxHeight * 1.8) continue;
            double overlapW = Math.min(right, ex + ew) - Math.max(left, ex);
            double overlapH = Math.min(bottom, ey + eh) - Math.max(top, ey);
            double overlap = Math.max(0, overlapW) * Math.max(0, overlapH);
            double centerX = ex + ew / 2, centerY = ey + eh / 2;
            boolean centerInside = centerX >= left && centerX <= right && centerY >= top && centerY <= bottom;
            boolean hit = centerInside || overlap >= ew * eh * 0.6;
            if (hit) selectedElementIds.add(element.getId());
        }
        elementGrid.getDataProvider().refreshAll();
        syncElementSelectAll();
        drawOverlay();
        Notification.show("已选中 " + selectedElementIds.size() + " 个元素");
    }

    private void toggleElementSelection(String elementId) {
        if (elementId == null) return;
        if (!selectedElementIds.remove(elementId)) selectedElementIds.add(elementId);
        elementGrid.getDataProvider().refreshAll();
        syncElementSelectAll();
        drawOverlay();
    }

    /** 浏览器工具条上的「拖拽交互」开关按钮。 */
    private Button dragModeButton() {
        Button button = button("拖拽交互", VaadinIcon.HAND, this::toggleMirrorDragMode);
        button.getElement().setAttribute("title",
                "开启后：在镜像里按住左键拖动会直接操作页面（滑块验证码、拖拽排序）；关闭时拖动是框选元素");
        dragModeButton = button;
        return button;
    }

    private void toggleMirrorDragMode() {
        mirrorDragMode = !mirrorDragMode;
        if (dragModeButton != null) {
            dragModeButton.setText(mirrorDragMode ? "拖拽交互：开" : "拖拽交互");
            dragModeButton.getElement().getClassList().set("drag-mode-on", mirrorDragMode);
        }
        applyMirrorDragMode();
        Notification.show(mirrorDragMode
                ? "拖拽交互已开启：按住左键拖动会直接操作页面（滑块验证码可用）；再点一次恢复框选"
                : "拖拽交互已关闭：拖动恢复为框选元素");
    }

    private void applyMirrorDragMode() {
        if (mirrorWrap.getParent().isEmpty()) return;
        mirrorWrap.getElement().executeJs("this.classList.toggle('mirror-drag-mode', $0)", mirrorDragMode);
    }

    private void forwardMouse(String phase, double x, double y) {
        if (sessionId == null) return;
        try { workspaceService.mouse(sessionId, phase, x, y); } catch (RuntimeException e) { showError(e); }
    }

    private void forwardClick(double x, double y, int button, int clickCount) {
        if (sessionId == null) return;
        try {
            workspaceService.click(sessionId, x, y, button, clickCount);
            refreshMirror();
        } catch (RuntimeException e) { showError(e); }
    }

    private void forwardWheel(double deltaX, double deltaY) {
        if (sessionId == null) return;
        try {
            workspaceService.wheel(sessionId, deltaX, deltaY);
            refreshMirror();
        } catch (RuntimeException e) { showError(e); }
    }

    /** 隐藏输入框只上报 “Control+a” 这类组合串，这里拆成基础键 + 修饰键转发给真实页面。 */
    private void forwardKeyCombo(String combo) {
        boolean control = combo != null && combo.startsWith("Control+");
        String base = control ? combo.substring("Control+".length()) : combo;
        workspaceService.key(sessionId, base, "", control, false, false);
    }

    private void forwardKey(String key, String text, boolean control, boolean alt, boolean shift) {
        if (sessionId == null) return;
        try {
            workspaceService.key(sessionId, key, text, control, alt, shift);
        } catch (RuntimeException e) { showError(e); }
    }

    private void syncMirror() {
        if (sessionId == null) return;
        try { refreshMirror(); } catch (RuntimeException e) { showError(e); }
    }

    private void capture() {
        if (sessionId == null) { Notification.show("请先启动浏览器"); return; }
        try {
            Screenshot screenshot = workspaceService.captureCurrent(sessionId);
            workspace = workspaceService.getProject(workspace.projectId);
            refreshMirror();
            saved("截图已保存到截图库");
            openShotRecognition(screenshot);
        } catch (RuntimeException e) { showError(e); }
    }

    /** 手动截图后的框选识别：框出区域 → 视觉模型识别 → 预入库功能点树 → 可直接生成用例。 */
    private void openShotRecognition(Screenshot screenshot) {
        if (systemSelect.getValue() == null) { Notification.show("请先选择被测系统"); return; }
        String systemId = systemSelect.getValue().getId();
        String systemName = systemSelect.getValue().getName();
        Dialog dialog = dialog("手动截图 · 框选识别");
        dialog.setWidth("920px");
        dialog.setHeight("92vh");
        // 一次识别要几十秒，按 Esc 或点一下外面就关掉太亏；只能显式点「关闭」。
        dialog.setCloseOnEsc(false);
        dialog.setCloseOnOutsideClick(false);

        Span meta = new Span("尺寸 " + screenshot.getWidth() + " × " + screenshot.getHeight()
                + " · 页面版本 " + screenshot.getPageRevision() + " · 文件 " + screenshot.getRelativePath());
        meta.addClassName("muted");
        Span selection = new Span("在截图上按住鼠标拖框选中要识别的区域：再拖一次就多一个框（搜索栏、操作按钮可以分别框），"
                + "框右上角 × 删单个；拖到上下边缘会自动滚屏，整张图都能框到。框好后点「AI 识别选区」。");
        selection.addClassName("muted");
        ShotRegionPicker picker = new ShotRegionPicker(workspaceService.screenshotData(screenshot),
                regions -> selection.setText(regionSummary(regions)));
        picker.setWidthFull();

        // 提示词是提高识别准确率的主要手段：模型只看图分不清「数据源选择弹窗」该怎么分组。
        TextArea hint = new TextArea("识别提示（可选）");
        hint.setPlaceholder("告诉模型这块是什么、该怎么建节点，例如：这是数据源类型选择弹窗，按「关系型数据库 / 大数据与数仓 / 国产数据库」三组归类，只建组名，不要逐个数据库建功能点");
        hint.setWidthFull();
        hint.setMinHeight("72px");
        hint.setMaxLength(600);

        List<byte[]> references = new ArrayList<>();
        Span refState = new Span("参考图可选：补一张同一页面的其他部分帮模型理解上下文，不会被当成识别对象（最多 3 张）");
        refState.addClassName("muted");
        UI currentUi = UI.getCurrent();
        Upload upload = new Upload(UploadHandler.inMemory((uploadMetadata, data) -> {
            if (currentUi == null) return;
            currentUi.access(() -> {
                references.add(data);
                refState.setText("已添加 " + references.size() + " 张参考图（" + uploadMetadata.fileName() + "）");
            });
        }));
        upload.setMaxFiles(3);
        upload.setMaxFileSize(6 * 1024 * 1024);
        upload.setAcceptedFileTypes("image/png", "image/jpeg", "image/webp");
        upload.setMaxHeight("84px");
        // 组件默认文案是英文，整个界面都是中文，这里全部换掉。
        upload.setUploadButton(new Button("添加参考图"));
        upload.setDropLabel(new Span("或把图片拖到这里"));
        upload.setI18n(new UploadI18N()
                .setAddFiles(new UploadI18N.AddFiles().setOne("添加参考图").setMany("添加参考图"))
                .setDropFiles(new UploadI18N.DropFiles().setOne("把图片拖到这里").setMany("把图片拖到这里"))
                .setError(new UploadI18N.Error()
                        .setTooManyFiles("最多 3 张参考图")
                        .setFileIsTooBig("单张图片不能超过 6 MB")
                        .setIncorrectFileType("只支持 PNG / JPG / WebP")));
        Button clearRefs = button("清除参考图", VaadinIcon.CLOSE_SMALL, () -> {
            references.clear();
            upload.clearFileList();
            refState.setText("参考图已清除");
        });

        VerticalLayout result = new VerticalLayout();
        result.setPadding(false);
        result.setSpacing(true);
        result.setWidthFull();

        Button recognize = primary("AI 识别选区", VaadinIcon.SEARCH, () ->
                recognizeRegion(screenshot, picker, systemId, systemName, selection, result, hint.getValue(), references));
        HorizontalLayout actions = new HorizontalLayout(recognize,
                button("清除选区", VaadinIcon.CLOSE_SMALL, picker::clearRegions),
                clearRefs,
                button("最近识别", VaadinIcon.CLOCK, this::openRecognitionHistory));
        actions.setAlignItems(Alignment.CENTER);

        // 识别按钮紧跟选区放在截图下方：加了提示词与参考图之后整列变长，主操作不能被挤到折叠线以下。
        VerticalLayout content = formBody(meta, picker, selection, actions, hint, upload, refState);
        content.add(result);
        dialog.add(content);
        Button close = new Button("关闭", event -> dialog.close());
        Span keepHint = new Span("识别结果会自动存进「最近识别」，关掉弹窗也能找回");
        keepHint.addClassName("muted");
        dialog.getFooter().add(close, keepHint);
        dialog.open();
    }

    /** 选区提示行的文案：几个框、各自多大，按框选顺序编号（AI 提示词里也是这个顺序）。 */
    static String regionSummary(List<int[]> regions) {
        if (regions == null || regions.isEmpty()) {
            return "还没有选区：在截图上拖一个框，可以连续拖多个";
        }
        StringBuilder text = new StringBuilder("已选 " + regions.size() + " 个区域：");
        for (int i = 0; i < regions.size(); i++) {
            int[] rect = regions.get(i);
            if (i > 0) text.append("、");
            text.append(i + 1).append("）").append(rect[2]).append(" × ").append(rect[3]);
        }
        text.append("（点框右上角 × 删单个，或点「清除选区」全部清掉）");
        return text.toString();
    }

    private void recognizeRegion(Screenshot screenshot, ShotRegionPicker picker, String systemId, String systemName,
                                 Span selection, VerticalLayout result, String hint, List<byte[]> references) {
        List<int[]> rects = picker.regions();
        if (rects.isEmpty()) { Notification.show("请先在截图上拖框选中至少一个区域"); return; }
        List<byte[]> crops = new ArrayList<>();
        try {
            for (int[] rect : rects) crops.add(cropScreenshot(screenshot.getRelativePath(), rect));
        } catch (RuntimeException e) { showError(e); return; }
        List<byte[]> refs = references == null ? List.of() : List.copyOf(references);
        selection.setText("正在识别 " + rects.size() + " 个区域"
                + (refs.isEmpty() ? "" : "（含 " + refs.size() + " 张参考图）")
                + (hint == null || hint.isBlank() ? "" : "（已带提示）")
                + (treeAdvisor.aiAvailable() ? "，视觉模型…" : "…"));
        String url = currentUrl();
        UI ui = UI.getCurrent();
        List<int[]> region = rects.stream().map(int[]::clone).toList();
        CompletableFuture.supplyAsync(() -> treeAdvisor.proposeFromRegion(crops, refs, hint, systemName, url, "截图选区"))
                .whenComplete((proposal, error) -> {
                    if (ui == null) return;
                    try {
                        ui.access(() -> {
                            if (error != null) {
                                log.warn("截图选区识别失败", error);
                                selection.setText("识别失败：" + rootMessage(error) + "；可以补一句识别提示或换个区域重试");
                                return;
                            }
                            renderRegionProposal(result, proposal, systemId, selection, screenshot, region, hint);
                        });
                    } catch (Throwable e) {
                        log.warn("截图识别结果回推界面失败", e);
                    }
                });
    }

    private void renderRegionProposal(VerticalLayout result, TreeAdvisor.RegionProposal proposal,
                                      String systemId, Span selection, Screenshot screenshot, List<int[]> rects, String hint) {
        if (selection != null) {
            selection.setText("识别完成：" + proposal.name()
                    + (proposal.summary() == null || proposal.summary().isBlank() ? "" : " · " + proposal.summary()));
        }
        List<DraftNode> drafts = new ArrayList<>();
        for (TreeAdvisor.Proposition item : proposal.nodes()) {
            drafts.add(new DraftNode(item.id(), item.name(), item.type(), item.parentId(), item.elementId()));
        }
        rememberRecognition(systemId, screenshot, rects, hint, proposal, drafts);
        renderDraftCards(result, drafts, systemId, proposal.name(), screenshot == null ? null : screenshot.getId());
        result.add(historyLine());
    }

    /** 识别结果一出来就落「最近识别」，误关弹窗、刷页面都能找回来。 */
    private void rememberRecognition(String systemId, Screenshot screenshot, List<int[]> rects, String hint,
                                     TreeAdvisor.RegionProposal proposal, List<DraftNode> drafts) {
        if (workspace == null) return;
        try {
            List<RecognitionHistoryStore.RegionDraft> nodes = drafts.stream()
                    .map(d -> new RecognitionHistoryStore.RegionDraft(d.id, d.name, d.type, d.parentId, d.elementId,
                            d.display))
                    .toList();
            String currentSystemName = systemSelect.getValue() == null ? "" : systemSelect.getValue().getName();
            recognitionHistory.add(new RecognitionHistoryStore.Entry(null, workspace.projectId, systemId,
                    currentSystemName, screenshot.getRelativePath(), rects, hint,
                    proposal.name(), proposal.summary(), 0L, nodes));
        } catch (RuntimeException e) {
            log.warn("识别记录保存失败：{}", e.getMessage());
        }
    }

    private Span historyLine() {
        Span line = new Span("已存入「最近识别」——关掉弹窗后可从顶部「最近识别」重新打开这份草稿");
        line.addClassName("muted");
        return line;
    }

    /** 把一份草稿渲染成可编辑、可入库的卡片区（识别结果与历史记录共用）。 */
    private void renderDraftCards(VerticalLayout result, List<DraftNode> drafts, String systemId,
                                  String regionName, String screenshotId) {
        result.removeAll();
        DraftRows rows = new DraftRows();
        Div summary = new Div();
        summary.addClassName("tree-draft-summary");
        Div scroll = new Div(rows);
        scroll.addClassName("tree-draft-scroll");
        AttachBar attach = new AttachBar();
        DraftEditor editor = new DraftEditor(drafts, rows, summary);
        editor.bind(attach);
        editor.render();

        Span saveState = new Span();
        saveState.addClassName("shot-save-state");
        Button toConsole = primary("生成用例 → 生成台", VaadinIcon.MAGIC, () ->
                saveAndGenerateDraft(drafts, systemId, null, attach, regionName, screenshotId));
        Button savePoints = button("只入功能点", VaadinIcon.FILTER, () -> {
            PersistResult persisted = persistDrafts(drafts, systemId, attach.rootParentId(), true);
            refreshTree();
            if (persisted.created() == 0 && persisted.reused() == 0) {
                saveState.setText(nothingSavedHint(persisted, true));
                Notification.show(nothingSavedHint(persisted, true));
                return;
            }
            revealUnder(attach.rootParentId());
            saveState.setText("已入库到「" + parentLabel(attach.rootParentId()) + "」：新建 "
                    + persisted.created() + " 个功能点"
                    + (persisted.reused() > 0 ? "，复用 " + persisted.reused() + " 个同名节点" : "")
                    + (persisted.omitted() > 0 ? "，忽略 " + persisted.omitted() + " 个目录节点" : ""));
            saved("已入库 " + persisted.created() + " 个功能点"
                    + (persisted.reused() > 0 ? "，复用 " + persisted.reused() + " 个同名节点" : "")
                    + (persisted.omitted() > 0 ? "，忽略 " + persisted.omitted() + " 个目录节点" : ""));
        });
        Button saveAll = button("入库（含目录）", VaadinIcon.SITEMAP, () -> {
            PersistResult persisted = persistDrafts(drafts, systemId, attach.rootParentId(), false);
            refreshTree();
            if (persisted.created() == 0 && persisted.reused() == 0) {
                saveState.setText(nothingSavedHint(persisted, false));
                Notification.show(nothingSavedHint(persisted, false));
                return;
            }
            revealUnder(attach.rootParentId());
            saveState.setText("已入库到「" + parentLabel(attach.rootParentId()) + "」：新建 "
                    + persisted.created() + " 个节点"
                    + (persisted.reused() > 0 ? "，复用 " + persisted.reused() + " 个同名节点" : ""));
            saved("已入库 " + persisted.created() + " 个节点到「" + parentLabel(attach.rootParentId()) + "」下"
                    + (persisted.reused() > 0 ? "，复用 " + persisted.reused() + " 个同名节点" : ""));
        });
        HorizontalLayout buttons = new HorizontalLayout(toConsole, savePoints, saveAll);
        buttons.setAlignItems(Alignment.CENTER);
        Span buttonsHint = new Span("「生成用例 → 生成台」只建功能点、不动用例；生成的用例进右侧生成台，确认无误再点「确认入库」。"
                + "草稿行是目录时用「入库（含目录）」，只入功能点会跳过目录。"
                + "读数、状态、时间这类只读内容把类型改成「显示项」：不建节点，按每项一条展示校验直接出用例。");
        buttonsHint.addClassName("muted");
        // 全是目录时「只入功能点」点了也是 0：直接置灰并写明该点哪个按钮，别让人反复无效点击。
        editor.onShapeChange(() -> syncDraftButtons(drafts, savePoints, saveAll, editor.effectiveCount()));
        result.add(attach, summary, editor.pickToolbar(), scroll, buttons, saveState, buttonsHint);
    }

    /** 最近识别记录：误关弹窗、刷新页面后从这里找回识别结果，继续入库或生成用例。 */
    private void openRecognitionHistory() {
        if (workspace == null) { Notification.show("请先加载项目"); return; }
        List<RecognitionHistoryStore.Entry> entries = recognitionHistory.recent(workspace.projectId, 20);
        Dialog dialog = dialog("最近识别（" + entries.size() + "）");
        dialog.setWidth("900px");
        VerticalLayout list = new VerticalLayout();
        list.setPadding(false);
        list.setSpacing(true);
        if (entries.isEmpty()) {
            Span empty = new Span("还没有识别记录：手动截图 → 拖框选区 → AI 识别，结果会自动记在这里，误关弹窗也能找回。");
            empty.addClassName("muted");
            list.add(empty);
        }
        for (RecognitionHistoryStore.Entry entry : entries) list.add(recognitionCard(entry, dialog));
        dialog.add(list);
        Button clear = button("清空记录", VaadinIcon.TRASH, () -> {
            recognitionHistory.clear(workspace.projectId);
            dialog.close();
            openRecognitionHistory();
        });
        dialog.getFooter().add(clear, new Button("关闭", event -> dialog.close()));
        dialog.open();
    }

    private Div recognitionCard(RecognitionHistoryStore.Entry entry, Dialog owner) {
        Div card = new Div();
        card.addClassName("recog-card");
        try {
            byte[] crop = cropScreenshot(entry.screenshotPath(), firstRect(entry));
            Image preview = new Image("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(crop),
                    "识别区域");
            preview.addClassName("recog-preview");
            card.add(preview);
        } catch (RuntimeException e) {
            Span missing = new Span("缩略图不可用（原截图已删除或旧记录没有选区）");
            missing.addClassName("recog-preview");
            missing.addClassName("recog-preview-missing");
            card.add(missing);
        }
        Div info = new Div();
        info.addClassName("recog-info");
        Span title = new Span(entry.regionName() == null || entry.regionName().isBlank() ? "未命名区域" : entry.regionName());
        title.addClassName("recog-title");
        Span meta = new Span(formatTimestamp(entry.createdAt())
                + (entry.systemName() == null || entry.systemName().isBlank() ? "" : " · " + entry.systemName())
                + " · " + (entry.nodes() == null ? 0 : entry.nodes().size()) + " 个节点"
                + (entry.rects() == null || entry.rects().size() <= 1 ? ""
                : " · " + entry.rects().size() + " 个选区"));
        meta.addClassName("muted");
        info.add(title, meta);
        if (entry.summary() != null && !entry.summary().isBlank()) {
            Span summary = new Span(entry.summary());
            summary.addClassName("muted");
            info.add(summary);
        }
        if (entry.hint() != null && !entry.hint().isBlank()) {
            Span hint = new Span("提示：" + entry.hint());
            hint.addClassName("recog-hint");
            info.add(hint);
        }
        Button open = button("打开草稿", VaadinIcon.EDIT, () -> {
            owner.close();
            openRestoredRecognition(entry);
        });
        Button remove = button("删除", VaadinIcon.TRASH, () -> {
            recognitionHistory.remove(workspace.projectId, entry.id());
            owner.close();
            openRecognitionHistory();
        });
        HorizontalLayout actions = new HorizontalLayout(open, remove);
        actions.setAlignItems(Alignment.CENTER);
        card.add(info, actions);
        return card;
    }

    /** 把一条历史记录还原成可编辑、可入库的草稿（与刚识别完的界面完全一样）。 */
    private void openRestoredRecognition(RecognitionHistoryStore.Entry entry) {
        Dialog dialog = dialog("识别记录 · " + (entry.regionName() == null || entry.regionName().isBlank()
                ? "未命名区域" : entry.regionName()));
        dialog.setWidth("920px");
        dialog.setHeight("88vh");
        String regionSize = "";
        if (entry.rects() != null && !entry.rects().isEmpty()) {
            regionSize = entry.rects().size() == 1
                    ? " · 区域 " + entry.rects().getFirst()[2] + " × " + entry.rects().getFirst()[3]
                    : " · " + entry.rects().size() + " 个选区";
        }
        Span meta = new Span(formatTimestamp(entry.createdAt())
                + (entry.systemName() == null || entry.systemName().isBlank() ? "" : " · " + entry.systemName())
                + " · " + (entry.nodes() == null ? 0 : entry.nodes().size()) + " 个节点" + regionSize
                + (entry.hint() == null || entry.hint().isBlank() ? "" : " · 原提示：" + entry.hint()));
        meta.addClassName("muted");
        VerticalLayout result = new VerticalLayout();
        result.setPadding(false);
        result.setSpacing(true);
        VerticalLayout content = formBody(meta);
        content.add(result);
        dialog.add(content);
        dialog.getFooter().add(new Button("关闭", event -> dialog.close()));
        dialog.open();
        List<DraftNode> drafts = new ArrayList<>();
        for (RecognitionHistoryStore.RegionDraft node : entry.nodes() == null
                ? List.<RecognitionHistoryStore.RegionDraft>of() : entry.nodes()) {
            drafts.add(new DraftNode(node.id(), node.name(), node.type(), node.parentId(), node.elementId(),
                    node.display()));
        }
        // 历史记录只存了截图的相对路径：按路径找回截图实体，生成用例时才挂得上「关联截图」。
        String screenshotId = entry.screenshotPath() == null || workspace == null ? null
                : workspace.screenshots.stream()
                        .filter(shot -> entry.screenshotPath().equals(shot.getRelativePath()))
                        .map(Screenshot::getId)
                        .findFirst().orElse(null);
        renderDraftCards(result, drafts, entry.systemId(), entry.regionName(), screenshotId);
    }

    private static String formatTimestamp(long epochMillis) {
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    private static int[] firstRect(RecognitionHistoryStore.Entry entry) {
        if (entry.rects() == null || entry.rects().isEmpty()) {
            throw new StorageException("这条记录没有选区信息");
        }
        return entry.rects().getFirst();
    }

    /** 按原始像素坐标裁剪已入库的截图（历史记录恢复缩略图时只拿得到相对路径）。 */
    private byte[] cropScreenshot(String relativePath, int[] rect) {
        java.nio.file.Path file = screenshotFileStore.resolve(relativePath);
        if (file == null) throw new StorageException("截图文件不存在：" + relativePath);
        try {
            java.awt.image.BufferedImage full = javax.imageio.ImageIO.read(file.toFile());
            if (full == null) throw new StorageException("截图无法解析");
            int x = Math.max(0, Math.min(rect[0], full.getWidth() - 1));
            int y = Math.max(0, Math.min(rect[1], full.getHeight() - 1));
            int width = Math.max(6, Math.min(rect[2], full.getWidth() - x));
            int height = Math.max(6, Math.min(rect[3], full.getHeight() - y));
            java.awt.image.BufferedImage sub = full.getSubimage(x, y, width, height);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(sub, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new StorageException("截图裁剪失败：" + e.getMessage());
        }
    }

    private void openScreenshotLibrary() {
        if (workspace == null) { Notification.show("请先加载项目"); return; }
        List<Screenshot> shots = workspace.screenshots.stream()
                .filter(s -> s.getDeletedAt() == null).toList();
        if (shots.isEmpty()) { Notification.show("截图库为空，请先在目标页面手动截图"); return; }
        Dialog dialog = dialog("截图库（" + shots.size() + "）");
        dialog.setWidth("960px");
        VerticalLayout content = new VerticalLayout();
        content.addClassName("shot-library");
        for (Screenshot screenshot : shots.stream().limit(50).toList()) {
            Div card = new Div();
            card.addClassName("shot-card");
            try {
                com.vaadin.flow.component.html.Image image =
                        new com.vaadin.flow.component.html.Image(workspaceService.screenshotData(screenshot), "截图");
                image.setWidth("280px");
                card.add(image);
            } catch (RuntimeException e) {
                card.add(new Span("截图文件缺失"));
            }
            card.add(new Span(screenshot.getWidth() + " × " + screenshot.getHeight()
                    + " · 页面版本 " + screenshot.getPageRevision()));
            content.add(card);
        }
        dialog.add(content);
        dialog.getFooter().add(new Button("关闭", event -> dialog.close()));
        dialog.open();
    }

    private void closeBrowser() {
        if (sessionId != null) workspaceService.closeBrowser(sessionId);
        sessionId = null;
        sessionSystemId = null;
        sessionEnvironmentId = null;
        zoomPercent = 100;
        browserStatus.setText("未启动");
    }
    private void browserAction(String action) {
        if (sessionId == null) { Notification.show("请先启动浏览器"); return; }
        try {
            switch (action) {
                case "后退" -> workspaceService.back(sessionId);
                case "刷新" -> workspaceService.reload(sessionId);
                default -> { }
            }
            zoomPercent = 100;
            refreshMirror();
        } catch (RuntimeException e) { showError(e); }
    }
    private void maximizeBrowser() {
        if (browserCanvas.getClassNames().contains("browser-maximized")) {
            browserCanvas.removeClassName("browser-maximized");
        } else {
            browserCanvas.addClassName("browser-maximized");
        }
    }
    private void openInNewTab() {
        String url = address.getValue();
        if (url == null || url.isBlank()) { Notification.show("请先输入地址"); return; }
        getUI().ifPresent(ui -> ui.getPage().open(url, "_blank"));
    }
    private void zoom(int value) {
        if (sessionId == null) { Notification.show("请先输入地址并前往目标系统"); return; }
        zoomPercent = value == 0 ? 100 : Math.max(50, Math.min(200, zoomPercent + value));
        try {
            workspaceService.zoom(sessionId, zoomPercent / 100.0);
            refreshMirror();
            Notification.show("当前缩放：" + zoomPercent + "%");
        } catch (RuntimeException e) { showError(e); }
    }
    private Checkbox autoScreenshot() { Checkbox checkbox = new Checkbox("自动截图"); checkbox.setValue(true); return checkbox; }
    private void openAutoTreeNotice() { if (requireWorkspace()) Notification.show("自动建树仅生成预览，经确认后只增不删"); }
    private void openModelSettings() {
        Dialog dialog = dialog("AI 模型配置");
        VerticalLayout body = new VerticalLayout();
        body.setPadding(false);
        body.setSpacing(true);
        body.add(new Span("当前状态：" + (aiSettings.configured() ? "已配置" : "未配置")),
                new Span("服务地址：" + (aiSettings.baseUrl().isBlank() ? "（未设置）" : aiSettings.baseUrl())),
                new Span("模型名称：" + (aiSettings.model().isBlank() ? "（未设置）" : aiSettings.model())),
                new Span("API 密钥：" + (aiSettings.hasApiKey() ? "已配置（不回显）" : "未配置")),
                hint("通过环境变量 QTS_AI_BASE_URL、QTS_AI_MODEL、QTS_AI_API_KEY 配置，修改后重启服务生效。"
                        + "当前版本尚未接入 AI 识别调用，此页只显示配置状态。"));
        dialog.add(body);
        dialog.setWidth("560px");
        dialog.getFooter().add(new Button("关闭", event -> dialog.close()));
        dialog.open();
    }
    private void confirmProjectDelete() { if (requireWorkspace()) { ConfirmDialog dialog = new ConfirmDialog(); dialog.setHeader("删除项目"); dialog.setText("项目删除保护正在接入，当前不会删除任何数据。"); dialog.setConfirmText("知道了"); dialog.open(); } }

    private void refreshProjects() {
        List<ProjectSummary> projects = workspaceService.listProjects();
        projectSelect.setItems(projects);
        if (projectSelect.getValue() == null && !projects.isEmpty()) {
            projectSelect.setValue(projects.getFirst());
            loadProject(projects.getFirst());
        }
    }
    private void refreshProjectsKeepSelection() { String id = workspace.projectId; refreshProjects(); projectSelect.getListDataView().getItems().filter(p -> p.id().equals(id)).findFirst().ifPresent(projectSelect::setValue); }
    private boolean requireWorkspace() { if (workspace == null) { Notification.show("请先创建并加载项目"); return false; } return true; }
    private void saved(String message) { saveStatus.setText(message); Notification.show(message); }
    private static boolean isBrowserClosed(RuntimeException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        return raw.contains("Target page, context or browser has been closed")
                || raw.contains("TargetClosedError") || raw.contains("浏览器会话已关闭")
                || raw.contains("浏览器会话不存在或已过期");
    }

    private void showError(RuntimeException e) {
        if (isBrowserClosed(e)) {
            sessionId = null;
            zoomPercent = 100;
            browserStatus.setText("未启动");
            resetBrowserCanvas("页面连接已断开，请重新点击“前往”");
            Notification.show("远程浏览器已关闭，请重新点击“前往”打开页面");
            return;
        }
        log.warn("界面操作失败：{}", describeFailure(e), e);
        Notification n = Notification.show(describeFailure(e));
        n.addThemeVariants(NotificationVariant.LUMO_ERROR);
    }

    /**
     * 未捕获的异常统一走这里：完整堆栈进日志，界面上只留最里层的原因。
     * Hibernate 把写库推迟到提交，超长/约束/断连最终都只报一句
     * “Unable to commit against JDBC Connection”，不给最里层信息根本没法定位。
     */
    private void installErrorHandler() {
        VaadinSession session = VaadinSession.getCurrent();
        if (session == null || errorHandlerInstalled) return;
        errorHandlerInstalled = true;
        session.setErrorHandler(event -> {
            Throwable failure = event.getThrowable();
            // 关页/刷新/热重启/断网时，Tomcat 往已断开的连接写 UIDL 响应会抛
            // ClientAbortException；这是浏览器走了，不是操作失败，不该按 ERROR 记堆栈。
            if (isClientGone(failure)) {
                log.debug("浏览器已断开，丢弃这次界面回推", failure);
                return;
            }
            log.error("界面操作失败", failure);
            Notification.show(describeFailure(failure));
        });
    }

    /** 连接被对端掐断（关页、刷新、断网、重启）引发的写失败，不算业务错误。 */
    static boolean isClientGone(Throwable error) {
        Set<Throwable> guard = new HashSet<>();
        for (Throwable current = error; current != null && guard.add(current); current = current.getCause()) {
            if (current instanceof org.apache.catalina.connector.ClientAbortException) return true;
            if (current instanceof java.nio.channels.ClosedChannelException) return true;
            if (current instanceof java.io.IOException) {
                String message = current.getMessage() == null ? ""
                        : current.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (message.contains("connection reset") || message.contains("broken pipe")
                        || message.contains("connection was aborted") || message.contains("established connection")
                        || message.contains("stream closed") || message.contains("连接已中止")
                        || message.contains("已建立的连接")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 取最里层的原因拼成一句提示，并顺带给出常见原因的处置建议。 */
    static String describeFailure(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        if (message == null || message.isBlank()) message = root.getClass().getSimpleName();
        message = message.lines().findFirst().orElse(message).trim();
        if (message.length() > 300) message = message.substring(0, 300) + "…";
        String hint = failureHint(message);
        return hint.isEmpty() ? message : message + "（" + hint + "）";
    }

    private static String failureHint(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        if (lower.contains("value too long") || lower.contains("character varying")) {
            return "某个字段超出库里的列长度，多半是用例的携带参数/备注写太长";
        }
        if (lower.contains("duplicate key")) return "主键冲突：这条数据已经存在，刷新后重试";
        if (lower.contains("does not exist") || lower.contains("undefined column")) return "库里缺列，检查是否漏跑迁移脚本";
        if (lower.contains("connection") || lower.contains("closed") || lower.contains("terminated")) {
            return "数据库连接已断，重试一次通常会恢复";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) return "操作超时，缩小范围或稍后重试";
        return "";
    }

    private void resetBrowserCanvas(String message) {
        browserCanvas.removeAll();
        VerticalLayout empty = new VerticalLayout(new Icon(VaadinIcon.DESKTOP), new Span(message));
        empty.setAlignItems(Alignment.CENTER);
        empty.addClassName("browser-empty");
        browserCanvas.add(empty);
    }

    private static VerticalLayout formBody(Component... components) {
        VerticalLayout body = new VerticalLayout(components);
        body.setPadding(false);
        body.setSpacing(true);
        body.setWidthFull();
        return body;
    }

    private static VerticalLayout panel() { VerticalLayout panel = new VerticalLayout(); panel.setPadding(false); panel.setSpacing(false); panel.setSizeFull(); panel.addClassName("tool-panel"); return panel; }
    private static HorizontalLayout panelTitle(String title, Component suffix) { HorizontalLayout row = new HorizontalLayout(new Span(title), suffix); row.addClassName("panel-title"); row.setAlignItems(Alignment.CENTER); return row; }
    private static Div hint(String text) { Div hint = new Div(text); hint.addClassName("hint-line"); return hint; }

    /** 小号问号图标：鼠标移上去（或键盘 Tab 聚焦）才展开说明，不再常年占掉两行树高。 */
    private static Component helpIcon(String... lines) {
        Span dot = new Span("?");
        dot.addClassName("help-dot");
        dot.getElement().setAttribute("tabindex", "0");
        dot.getElement().setAttribute("role", "img");
        dot.getElement().setAttribute("aria-label", "操作说明");
        Div pop = new Div();
        pop.addClassName("help-pop");
        for (String line : lines) {
            Div item = new Div(line);
            item.addClassName("help-pop-line");
            pop.add(item);
        }
        Div wrap = new Div(dot, pop);
        wrap.addClassName("help-wrap");
        return wrap;
    }
    private static Span badge(String text) { Span badge = new Span(text); badge.addClassName("status-badge"); return badge; }
    private static Button button(String text, VaadinIcon icon, Runnable action) { Button button = new Button(text, icon == null ? null : icon.create(), event -> action.run()); button.addClassName("tool-button"); return button; }
    private static Button primary(String text, VaadinIcon icon, Runnable action) { Button button = button(text, icon, action); button.addThemeVariants(ButtonVariant.LUMO_PRIMARY); return button; }
    private static Button iconButton(String title, VaadinIcon icon, Runnable action) { Button button = new Button(icon.create(), event -> action.run()); button.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE); button.setAriaLabel(title); button.setTooltipText(title); return button; }
    private static TextField requiredField(String label) { TextField field = new TextField(label); field.setRequired(true); field.setWidthFull(); return field; }
    private void footer(Dialog dialog, String text, Runnable action) { Button save = primary(text, null, () -> { try { action.run(); } catch (RuntimeException e) { showError(e); } }); dialog.getFooter().add(new Button("取消", e -> dialog.close()), save); }
    private String typeName(NodeType type) { return switch (type) { case MODULE -> "模块"; case TITLE1 -> "一级目录"; case TITLE2 -> "二级目录"; case TITLE3 -> "三级目录"; case POINT -> "功能点"; }; }
}
