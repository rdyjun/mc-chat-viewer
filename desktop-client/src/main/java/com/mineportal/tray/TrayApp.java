package com.mineportal.tray;

import com.mineportal.BuildInfo;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/**
 * 이 앱의 유일한 화면 요소 — 창은 전혀 없고, 시스템 트레이 아이콘 하나로만 존재를
 * 알린다(우측 하단, 작업표시줄 트레이). 로그인/서버 목록/연결/채팅은 전부 웹
 * (mineportal.kr)이 표시하고, 이 앱은 BackendClient를 통해 그걸 그대로 수행만 한다.
 */
public final class TrayApp {

    private TrayIcon trayIcon;
    private volatile boolean backendConnected = false;

    public void start() {
        if (!SystemTray.isSupported()) {
            return; // 트레이가 없는 환경 — 백그라운드로만 계속 동작한다.
        }

        PopupMenu menu = new PopupMenu();
        MenuItem exit = new MenuItem("종료");
        exit.addActionListener(e -> System.exit(0));
        menu.add(exit);

        TrayIcon icon = new TrayIcon(loadLogo(), "마인포탈 — 대기 중", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(e -> icon.displayMessage(
                "마인포탈 v" + BuildInfo.VERSION,
                backendConnected ? "동작 중입니다." : "서버에 연결하는 중...",
                TrayIcon.MessageType.NONE));

        try {
            SystemTray.getSystemTray().add(icon);
            trayIcon = icon;
        } catch (AWTException ignored) {
        }
    }

    /** BackendClient가 백엔드 WS 연결 상태가 바뀔 때마다 호출한다 — 트레이 툴팁에 반영. */
    public void setBackendConnected(boolean connected) {
        backendConnected = connected;
        if (trayIcon != null) {
            trayIcon.setToolTip(connected ? "마인포탈 — 동작 중" : "마인포탈 — 서버 연결 중...");
        }
    }

    private static Image loadLogo() {
        try (InputStream in = TrayApp.class.getResourceAsStream("/logo.png")) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) return img.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
            }
        } catch (Exception ignored) {
        }
        return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
    }

}
