package com.yuceyang.uitest.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;

/**
 * 应用外壳配置。@Push 必须放在 AppShellConfigurator 上：
 * 用例生成在后台线程结束后用 ui.access 回推界面，没有 Push 时会在无头/已关闭的会话上卡住。
 */
@Push
public class AppShell implements AppShellConfigurator {
}
