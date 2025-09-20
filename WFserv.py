# --- BLOQUE: imports y constantes INICIO ---
import socket
import threading
import sys
from typing import Optional

from PyQt5.QtCore import Qt, pyqtSignal, QObject
from PyQt5.QtWidgets import QApplication, QWidget, QLabel, QPushButton, QVBoxLayout

import pyaudio

APP_TITLE = "Wifi-AudioRT"

DISCOVERY_PORT = 50006                  # cinco dígitos
AUDIO_PORT = 50005                      # cinco dígitos
DISCOVERY_REQUEST = b"DISCOVER_WIFI_AUDIO_SERVER"
SERVER_NAME = "Wifi-AudioRT"
DISCOVERY_REPLY = f"WifiAudioTX-Server|{SERVER_NAME}|{AUDIO_PORT}".encode("ascii")

RATE = 48000
CHANNELS = 2
FORMAT = pyaudio.paInt16
FRAMES_PER_PACKET = 960                 # ~20 ms
# --- BLOQUE: imports y constantes FIN ---


# --- BLOQUE: UiBus INICIO ---
class UiBus(QObject):
    set_status = pyqtSignal(str)
    enable_stop = pyqtSignal(bool)
# --- BLOQUE: UiBus FIN ---


# --- BLOQUE: AudioPlayer INICIO ---
class AudioPlayer:
    def __init__(self):
        self.pa = pyaudio.PyAudio()
        self.stream = None

    def open(self):
        if self.stream:
            return
        self.stream = self.pa.open(
            format=FORMAT,
            channels=CHANNELS,
            rate=RATE,
            output=True,
            frames_per_buffer=FRAMES_PER_PACKET,
        )

    def write(self, data: bytes):
        if self.stream:
            self.stream.write(data, exception_on_underflow=False)

    def close(self):
        if self.stream:
            self.stream.stop_stream()
            self.stream.close()
            self.stream = None

    def terminate(self):
        self.close()
        self.pa.terminate()
# --- BLOQUE: AudioPlayer FIN ---


# --- BLOQUE: DiscoveryServer INICIO ---
class DiscoveryServer(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.sock = None
        self.running = threading.Event()

    def run(self):
        self.running.set()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("", DISCOVERY_PORT))

        while self.running.is_set():
            try:
                data, addr = self.sock.recvfrom(1024)
            except OSError:
                break
            if data == DISCOVERY_REQUEST:
                # Responder con nombre y puerto de audio para que la app lo liste
                self.sock.sendto(DISCOVERY_REPLY, addr)

    def stop(self):
        self.running.clear()
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
# --- BLOQUE: DiscoveryServer FIN ---


# --- BLOQUE: UdpAudioServer INICIO ---
class UdpAudioServer(threading.Thread):
    def __init__(self, port: int, ui: UiBus):
        super().__init__(daemon=True)
        self.port = port
        self.ui = ui
        self.sock: Optional[socket.socket] = None
        self.player = AudioPlayer()
        self.running = threading.Event()
        self.accepting = threading.Event()
        self.connected = False
        self.client_addr: Optional[tuple[str, int]] = None

    def run(self):
        self.running.set()
        self.accepting.clear()
        self.connected = False
        self.client_addr = None
        self.ui.set_status.emit("Servidor en espera...")
        self.ui.enable_stop.emit(False)

        self.player.open()
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 2 * 1024 * 1024)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("", self.port))
        self.sock.settimeout(0.5)

        try:
            while self.running.is_set():
                try:
                    data, addr = self.sock.recvfrom(65536)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if not data:
                    continue

                # Primer paquete: marcar conectado y enviar ACK para destrabar al cliente
                if not self.connected:
                    self.connected = True
                    self.accepting.set()
                    self.client_addr = addr
                    self.ui.set_status.emit("Conectado a -")
                    self.ui.enable_stop.emit(True)
                    try:
                        self.sock.sendto(b"OK", addr)  # ACK simple
                    except Exception:
                        pass

                # Si la app hace handshake con texto, igual marcamos conectado
                if data.startswith(b"HELLO") or data.startswith(b"CONNECT"):
                    try:
                        self.sock.sendto(b"OK", addr)
                    except Exception:
                        pass
                    continue

                # Reproducción PCM 16-bit LE estéreo
                if self.accepting.is_set():
                    if len(data) & 1 == 0:  # múltiplo de 2
                        self.player.write(data)
                    # si cae impar, lo ignoramos

        finally:
            self._cleanup()

    def stop(self):
        self.running.clear()
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass

    def disconnect(self):
        self.accepting.clear()
        self.connected = False
        self.client_addr = None
        self.ui.enable_stop.emit(False)
        self.ui.set_status.emit("Servidor en espera...")

    def _cleanup(self):
        self.accepting.clear()
        self.connected = False
        self.client_addr = None
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None
        self.player.terminate()
        self.ui.enable_stop.emit(False)
        self.ui.set_status.emit("Servidor en espera...")
# --- BLOQUE: UdpAudioServer FIN ---


# --- BLOQUE: ServerWindow INICIO ---
class ServerWindow(QWidget):
    def __init__(self, port: int):
        super().__init__()
        self.setWindowTitle(APP_TITLE)

        self.ui = UiBus()

        self.status_lbl = QLabel("Servidor en espera...")
        self.status_lbl.setAlignment(Qt.AlignCenter)
        self.status_lbl.setStyleSheet("font-size:16px;")

        self.stop_btn = QPushButton("Detener servidor")
        self.stop_btn.setEnabled(False)
        self.stop_btn.clicked.connect(self.on_stop_clicked)

        layout = QVBoxLayout()
        layout.addWidget(self.status_lbl)
        layout.addWidget(self.stop_btn)
        self.setLayout(layout)
        self.setFixedSize(360, 140)

        self.ui.set_status.connect(self.status_lbl.setText)
        self.ui.enable_stop.connect(self.stop_btn.setEnabled)

        self.discovery = DiscoveryServer()
        self.discovery.start()

        self.server = UdpAudioServer(port, self.ui)
        self.server.start()

    def on_stop_clicked(self):
        self.server.disconnect()

    def closeEvent(self, event):
        try:
            if self.server:
                self.server.stop()
                self.server.join(timeout=1.5)
            if self.discovery:
                self.discovery.stop()
                self.discovery.join(timeout=1.5)
        finally:
            event.accept()
# --- BLOQUE: ServerWindow FIN ---


# --- BLOQUE: main INICIO ---
def main():
    port = AUDIO_PORT
    if len(sys.argv) >= 2:
        try:
            port = int(sys.argv[1])
        except ValueError:
            port = AUDIO_PORT

    app = QApplication(sys.argv)
    w = ServerWindow(port)
    w.show()
    sys.exit(app.exec_())

if __name__ == "__main__":
    main()
# --- BLOQUE: main FIN ---
