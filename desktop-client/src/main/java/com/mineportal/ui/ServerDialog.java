package com.mineportal.ui;

import com.mineportal.store.ServerEntry;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

import java.awt.GridLayout;
import java.awt.Window;

/** Modal dialog to enter a new server (name / host / port / version). */
final class ServerDialog {

    static ServerEntry show(Window parent) {
        JTextField name = new JTextField();
        JTextField host = new JTextField();
        JTextField port = new JTextField("25565");
        JTextField version = new JTextField("1.21.7");

        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel("이름 (선택)"));
        panel.add(name);
        panel.add(new JLabel("호스트"));
        panel.add(host);
        panel.add(new JLabel("포트"));
        panel.add(port);
        panel.add(new JLabel("버전 (참고용)"));
        panel.add(version);

        while (true) {
            int result = JOptionPane.showConfirmDialog(parent, panel, "서버 추가",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            String hostText = host.getText().trim();
            if (hostText.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "호스트를 입력하세요.", "입력 오류",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            int portValue;
            try {
                portValue = Integer.parseInt(port.getText().trim());
                if (portValue < 1 || portValue > 65535) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(parent, "포트는 1~65535 사이의 숫자여야 합니다.", "입력 오류",
                        JOptionPane.WARNING_MESSAGE);
                continue;
            }
            return new ServerEntry(name.getText().trim(), hostText, portValue, version.getText().trim());
        }
    }

    private ServerDialog() {
    }
}
