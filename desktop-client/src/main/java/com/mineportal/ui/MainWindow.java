package com.mineportal.ui;

import com.mineportal.BuildInfo;
import com.mineportal.auth.Account;
import com.mineportal.auth.AuthService;
import com.mineportal.mc.ChatClient;
import com.mineportal.store.ServerEntry;
import com.mineportal.store.ServerStore;
import com.mineportal.update.Updater;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import java.awt.BorderLayout;
import java.awt.datatransfer.StringSelection;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public final class MainWindow extends JFrame {

    private final AuthService authService = new AuthService();
    private final Updater updater = new Updater();

    private Account account;
    private ChatClient client;

    private final DefaultListModel<ServerEntry> serverModel = new DefaultListModel<>();
    private final JList<ServerEntry> serverList = new JList<>(serverModel);

    private final JLabel loginStatus = new JLabel();
    private final JButton loginButton = new JButton("로그인");
    private final JButton connectButton = new JButton("접속");
    private final JButton addButton = new JButton("추가");
    private final JButton removeButton = new JButton("삭제");

    private final JTextArea chatLog = new JTextArea();
    private final JTextField chatInput = new JTextField();
    private final JButton sendButton = new JButton("전송");

    private JDialog deviceCodeDialog;

    public MainWindow() {
        super("MinePortal 데스크톱 - v" + BuildInfo.VERSION);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(860, 560);
        setLocationRelativeTo(null);

        buildUi();
        loadServers();
        refreshState();

        restoreSessionAsync();
        checkForUpdatesAsync();
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));

        // Top: login status + version
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(loginButton);
        left.add(loginStatus);
        top.add(left, BorderLayout.WEST);
        top.add(new JLabel("v" + BuildInfo.VERSION + "  "), BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // Left: server list + buttons
        JPanel side = new JPanel(new BorderLayout(4, 4));
        side.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        side.setPreferredSize(new Dimension(280, 0));
        serverList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        serverList.addListSelectionListener(e -> refreshState());
        side.add(new JLabel("서버 목록"), BorderLayout.NORTH);
        side.add(new JScrollPane(serverList), BorderLayout.CENTER);
        JPanel sideButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        sideButtons.add(addButton);
        sideButtons.add(removeButton);
        sideButtons.add(connectButton);
        side.add(sideButtons, BorderLayout.SOUTH);
        add(side, BorderLayout.WEST);

        // Center: chat log + input
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        chatLog.setEditable(false);
        chatLog.setLineWrap(true);
        chatLog.setWrapStyleWord(true);
        center.add(new JScrollPane(chatLog), BorderLayout.CENTER);
        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.add(chatInput, BorderLayout.CENTER);
        inputRow.add(sendButton, BorderLayout.EAST);
        center.add(inputRow, BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        loginButton.addActionListener(e -> onLoginOrLogout());
        addButton.addActionListener(e -> onAddServer());
        removeButton.addActionListener(e -> onRemoveServer());
        connectButton.addActionListener(e -> onConnectOrDisconnect());
        sendButton.addActionListener(e -> onSend());
        chatInput.addActionListener(e -> onSend());
    }

    // ---- state ----

    private boolean isLoggedIn() {
        return account != null;
    }

    private boolean isConnected() {
        return client != null && client.isConnected();
    }

    private void refreshState() {
        loginStatus.setText(isLoggedIn() ? ("로그인됨: " + account.name) : "로그인되지 않음");
        loginButton.setText(isLoggedIn() ? "로그아웃" : "로그인");

        boolean canConnect = isLoggedIn() && serverList.getSelectedValue() != null;
        connectButton.setEnabled(canConnect || isConnected());
        connectButton.setText(isConnected() ? "접속 종료" : "접속");
        addButton.setEnabled(!isConnected());
        removeButton.setEnabled(!isConnected() && serverList.getSelectedValue() != null);
        serverList.setEnabled(!isConnected());

        boolean chatEnabled = isConnected();
        chatInput.setEnabled(chatEnabled);
        sendButton.setEnabled(chatEnabled);
    }

    private void append(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        chatLog.append("[" + ts + "] " + line + "\n");
        chatLog.setCaretPosition(chatLog.getDocument().getLength());
    }

    // ---- login ----

    private void onLoginOrLogout() {
        if (isLoggedIn()) {
            if (isConnected()) {
                client.disconnect();
            }
            authService.logout();
            account = null;
            append("로그아웃되었습니다.");
            refreshState();
            return;
        }

        loginButton.setEnabled(false);
        append("Microsoft 로그인 시작...");
        new Thread(() -> {
            try {
                Account result = authService.login(this::showDeviceCode);
                SwingUtilities.invokeLater(() -> {
                    closeDeviceCodeDialog();
                    account = result;
                    append("로그인 성공: " + result.name);
                    loginButton.setEnabled(true);
                    refreshState();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    closeDeviceCodeDialog();
                    loginButton.setEnabled(true);
                    append("로그인 실패: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, "로그인에 실패했습니다:\n" + ex.getMessage(),
                            "로그인 오류", JOptionPane.ERROR_MESSAGE);
                    refreshState();
                });
            }
        }, "mineportal-login").start();
    }

    private void showDeviceCode(AuthService.DeviceCode code) {
        SwingUtilities.invokeLater(() -> {
            closeDeviceCodeDialog();
            JDialog dialog = new JDialog(this, "Microsoft 로그인", false);
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            panel.add(new JLabel("아래 주소를 브라우저에서 열고 코드를 입력하세요:"));
            panel.add(Box.createVerticalStrut(6));
            panel.add(new JLabel(code.verificationUri));
            panel.add(Box.createVerticalStrut(6));
            JLabel codeLabel = new JLabel("코드: " + code.userCode);
            codeLabel.setFont(codeLabel.getFont().deriveFont(codeLabel.getFont().getSize() + 4f));
            panel.add(codeLabel);
            panel.add(Box.createVerticalStrut(10));

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            JButton open = new JButton("브라우저 열기");
            open.addActionListener(e -> openBrowser(code.directVerificationUri));
            JButton copy = new JButton("코드 복사");
            copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(code.userCode), null));
            buttons.add(open);
            buttons.add(copy);
            panel.add(buttons);

            dialog.setContentPane(panel);
            dialog.pack();
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
            deviceCodeDialog = dialog;
        });
    }

    private void closeDeviceCodeDialog() {
        if (deviceCodeDialog != null) {
            deviceCodeDialog.dispose();
            deviceCodeDialog = null;
        }
    }

    private void restoreSessionAsync() {
        new Thread(() -> {
            Account restored = authService.restoreSession();
            if (restored != null) {
                SwingUtilities.invokeLater(() -> {
                    account = restored;
                    append("저장된 세션으로 로그인됨: " + restored.name);
                    refreshState();
                });
            }
        }, "mineportal-restore").start();
    }

    // ---- servers ----

    private void loadServers() {
        serverModel.clear();
        for (ServerEntry entry : ServerStore.load()) {
            serverModel.addElement(entry);
        }
    }

    private void persistServers() {
        List<ServerEntry> list = new java.util.ArrayList<>();
        for (int i = 0; i < serverModel.size(); i++) {
            list.add(serverModel.get(i));
        }
        ServerStore.save(list);
    }

    private void onAddServer() {
        ServerEntry entry = ServerDialog.show(this);
        if (entry != null) {
            serverModel.addElement(entry);
            persistServers();
            refreshState();
        }
    }

    private void onRemoveServer() {
        int idx = serverList.getSelectedIndex();
        if (idx >= 0) {
            serverModel.remove(idx);
            persistServers();
            refreshState();
        }
    }

    // ---- connection ----

    private void onConnectOrDisconnect() {
        if (isConnected()) {
            client.disconnect();
            return;
        }
        ServerEntry entry = serverList.getSelectedValue();
        if (entry == null || account == null) {
            return;
        }
        append("접속 중: " + entry.host + ":" + entry.port);
        connectButton.setEnabled(false);

        ChatClient newClient = new ChatClient(account, new ChatClient.Listener() {
            @Override
            public void onConnected() {
                SwingUtilities.invokeLater(() -> {
                    append("서버에 연결되었습니다.");
                    refreshState();
                });
            }

            @Override
            public void onChat(String line) {
                SwingUtilities.invokeLater(() -> append(line));
            }

            @Override
            public void onDisconnected(String reason) {
                SwingUtilities.invokeLater(() -> {
                    append("접속 종료: " + reason);
                    refreshState();
                });
            }
        });
        this.client = newClient;

        new Thread(() -> {
            try {
                newClient.connect(entry.host, entry.port);
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    append("접속 실패: " + ex.getMessage());
                    refreshState();
                });
            }
        }, "mineportal-connect").start();
    }

    private void onSend() {
        String text = chatInput.getText().trim();
        if (text.isEmpty() || !isConnected()) {
            return;
        }
        client.sendChat(text);
        append("나: " + text);
        chatInput.setText("");
    }

    // ---- updates ----

    private void checkForUpdatesAsync() {
        new Thread(() -> {
            try {
                Updater.ReleaseInfo release = updater.checkForUpdate();
                if (release != null) {
                    SwingUtilities.invokeLater(() -> promptUpdate(release));
                }
            } catch (Exception ignored) {
            }
        }, "mineportal-update").start();
    }

    private void promptUpdate(Updater.ReleaseInfo release) {
        Object[] options = {"다운로드 및 재시작", "릴리즈 페이지 열기", "나중에"};
        int choice = JOptionPane.showOptionDialog(this,
                "새 버전 " + release.tag + " 이(가) 있습니다. (현재 v" + BuildInfo.VERSION + ")",
                "업데이트 확인",
                JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            boolean started = updater.applyUpdate(release);
            if (started) {
                append("업데이트를 다운로드했습니다. 프로그램을 재시작합니다...");
                Timer timer = new Timer(1200, e -> System.exit(0));
                timer.setRepeats(false);
                timer.start();
            } else {
                append("자동 업데이트를 사용할 수 없습니다. 릴리즈 페이지를 엽니다.");
                openBrowser(release.htmlUrl);
            }
        } else if (choice == 1) {
            openBrowser(release.htmlUrl);
        }
    }

    private void openBrowser(String url) {
        if (url == null) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ex) {
            append("브라우저를 열지 못했습니다: " + url);
        }
    }
}
