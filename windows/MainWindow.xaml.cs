using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Microsoft.Win32;

namespace DrawTabletPC
{
    public partial class MainWindow : Window
    {
        private const int SurfaceWidth = 1024;
        private const int SurfaceHeight = 576;
        private const int White = unchecked((int)0xFFFFFFFF);

        private sealed class DrawingLayer
        {
            public int Id { get; }
            public string Name { get; set; }
            public bool Visible { get; set; } = true;
            public int[] Pixels { get; } = new int[SurfaceWidth * SurfaceHeight];

            public DrawingLayer(int id, string name)
            {
                Id = id;
                Name = name;
            }
        }

        private TcpListener? _server;
        private Thread? _serverThread;
        private volatile bool _running;
        private readonly List<DrawingLayer> _layers = new();
        private DrawingLayer _activeLayer = null!;
        private readonly int[] _compositePixels = new int[SurfaceWidth * SurfaceHeight];
        private readonly WriteableBitmap _displayBitmap;
        private readonly DispatcherTimer _renderTimer;
        private bool _renderPending = true;
        private int _receivedCommands;
        private int _lastX;
        private int _lastY;
        private int _strokeColor = unchecked((int)0xFF000000);
        private int _strokeRadius = 3;
        private bool _erasing;
        private bool _strokeActive;

        public MainWindow()
        {
            InitializeComponent();

            _activeLayer = new DrawingLayer(0, "Base");
            _layers.Add(_activeLayer);
            _displayBitmap = new WriteableBitmap(
                SurfaceWidth, SurfaceHeight, 96, 96, PixelFormats.Bgra32, null);
            DrawImage.Source = _displayBitmap;

            _renderTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(16) };
            _renderTimer.Tick += (_, _) =>
            {
                if (_renderPending)
                {
                    ComposeAndRender();
                    _renderPending = false;
                }
            };
            _renderTimer.Start();

            Loaded += OnLoaded;
            Closing += OnClosing;
            UpdateLayerLabel();
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            string ip = GetLocalIP();
            IpText.Text = $"IP: {ip} | Porta: 9999";
            InfoText.Text = $"Inserisci nell'app Android → IP: {ip}";
            StartServer();
        }

        private static string GetLocalIP()
        {
            try
            {
                using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, 0);
                socket.Connect("8.8.8.8", 65530);
                return (socket.LocalEndPoint as IPEndPoint)?.Address.ToString() ?? "127.0.0.1";
            }
            catch
            {
                return "127.0.0.1";
            }
        }

        private void StartServer()
        {
            _running = true;
            _serverThread = new Thread(() =>
            {
                try
                {
                    _server = new TcpListener(IPAddress.Any, 9999);
                    _server.Start();

                    while (_running)
                    {
                        try
                        {
                            using var client = _server.AcceptTcpClient();
                            client.NoDelay = true;
                            _receivedCommands = 0;
                            Dispatcher.BeginInvoke(() =>
                            {
                                StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0x43, 0xA0, 0x47));
                                StatusText.Text = "✅ Tablet connesso — in attesa del primo tratto";
                            });
                            HandleClient(client);
                        }
                        catch (SocketException)
                        {
                            if (!_running) break;
                        }
                    }
                }
                catch (Exception ex)
                {
                    Dispatcher.BeginInvoke(() => StatusText.Text = $"Errore server: {ex.Message}");
                }
            }) { IsBackground = true };
            _serverThread.Start();
        }

        private void HandleClient(TcpClient client)
        {
            using var reader = new StreamReader(client.GetStream());
            try
            {
                string? line;
                while (_running && (line = reader.ReadLine()) != null)
                    ProcessCommand(line);
            }
            catch (Exception ex)
            {
                Dispatcher.BeginInvoke(() => StatusText.Text = $"Errore di rete: {ex.Message}");
            }
            finally
            {
                Dispatcher.BeginInvoke(() =>
                {
                    StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xEF, 0x53, 0x50));
                    StatusText.Text = "Tablet disconnesso. In attesa...";
                });
            }
        }

        private void ProcessCommand(string line)
        {
            line = line.Trim();
            if (line.Length == 0) return;
            string[] parts = line.Split('|');

            Dispatcher.BeginInvoke(() =>
            {
                string command = parts[0].Trim().ToUpperInvariant();
                try
                {
                    switch (command)
                    {
                        case "DOWN" when parts.Length >= 6:
                            HandleDown(parts);
                            break;
                        case "MOVE" when parts.Length >= 3:
                            HandleMove(parts);
                            break;
                        case "UP" when parts.Length >= 3:
                            HandleUp(parts);
                            break;
                        case "CLEAR":
                            ClearAllLayers();
                            break;
                        case "FILL" when parts.Length >= 4:
                            HandleFill(parts);
                            break;
                        case "LAYER" when parts.Length >= 3:
                            HandleLayerCommand(parts);
                            break;
                        default:
                            throw new FormatException($"comando non riconosciuto: {command}");
                    }

                    _receivedCommands++;
                    StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0x43, 0xA0, 0x47));
                    StatusText.Text = $"✅ Ricezione attiva — {_receivedCommands} comandi (ultimo: {command})";
                }
                catch (Exception ex)
                {
                    StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xFF, 0xA7, 0x26));
                    StatusText.Text = $"Comando non valido ({command}): {ex.Message}";
                }
            });
        }

        private void HandleDown(string[] parts)
        {
            (_lastX, _lastY) = ParsePoint(parts[1], parts[2]);
            _strokeColor = ParseColor(parts[3]);
            double stroke = ParseNumber(parts[4]);
            string tool = parts[5].Trim().ToUpperInvariant();
            _erasing = tool == "ERASER";
            _strokeRadius = Math.Max(1, (int)Math.Round(stroke * SurfaceWidth / 1080.0 / 2.0));
            if (tool == "BRUSH")
            {
                _strokeRadius *= 2;
                _strokeColor = (_strokeColor & 0x00FFFFFF) | unchecked((int)0xB4000000);
            }
            DrawCircle(_lastX, _lastY);
            _strokeActive = true;
            RequestRender();
        }

        private void HandleMove(string[] parts)
        {
            if (!_strokeActive) return;
            var (x, y) = ParsePoint(parts[1], parts[2]);
            DrawLine(_lastX, _lastY, x, y);
            _lastX = x;
            _lastY = y;
            RequestRender();
        }

        private void HandleUp(string[] parts)
        {
            if (!_strokeActive) return;
            var (x, y) = ParsePoint(parts[1], parts[2]);
            DrawLine(_lastX, _lastY, x, y);
            _strokeActive = false;
            RequestRender();
        }

        private void HandleFill(string[] parts)
        {
            var (x, y) = ParsePoint(parts[1], parts[2]);
            int newColor = ParseColor(parts[3]);
            ComposePixels();
            int start = y * SurfaceWidth + x;
            int targetColor = _compositePixels[start];
            if (targetColor == newColor) return;

            var visited = new bool[_compositePixels.Length];
            var queue = new Queue<int>();
            queue.Enqueue(start);
            visited[start] = true;

            while (queue.Count > 0)
            {
                int index = queue.Dequeue();
                if (_compositePixels[index] != targetColor) continue;
                _activeLayer.Pixels[index] = newColor;
                int px = index % SurfaceWidth;
                int py = index / SurfaceWidth;

                TryQueue(index - 1, px > 0);
                TryQueue(index + 1, px < SurfaceWidth - 1);
                TryQueue(index - SurfaceWidth, py > 0);
                TryQueue(index + SurfaceWidth, py < SurfaceHeight - 1);
            }

            RequestRender();

            void TryQueue(int index, bool inBounds)
            {
                if (inBounds && !visited[index] && _compositePixels[index] == targetColor)
                {
                    visited[index] = true;
                    queue.Enqueue(index);
                }
            }
        }

        private void HandleLayerCommand(string[] parts)
        {
            string action = parts[1].Trim().ToUpperInvariant();
            int id = int.Parse(parts[2], CultureInfo.InvariantCulture);

            switch (action)
            {
                case "ADD":
                    if (_layers.All(layer => layer.Id != id))
                    {
                        string name = parts.Length >= 4 ? parts[3].Trim() : $"Livello {id + 1}";
                        _layers.Add(new DrawingLayer(id, name));
                    }
                    break;
                case "SELECT":
                    _activeLayer = _layers.FirstOrDefault(layer => layer.Id == id)
                        ?? throw new FormatException($"livello {id} inesistente");
                    break;
                case "DELETE":
                    if (_layers.Count > 1)
                    {
                        DrawingLayer? layer = _layers.FirstOrDefault(item => item.Id == id);
                        if (layer != null) _layers.Remove(layer);
                        if (!_layers.Contains(_activeLayer)) _activeLayer = _layers[^1];
                    }
                    break;
                case "VISIBLE" when parts.Length >= 4:
                    DrawingLayer visibleLayer = _layers.FirstOrDefault(layer => layer.Id == id)
                        ?? throw new FormatException($"livello {id} inesistente");
                    visibleLayer.Visible = bool.Parse(parts[3]);
                    break;
                case "CLEAR":
                    DrawingLayer clearLayer = _layers.FirstOrDefault(layer => layer.Id == id)
                        ?? throw new FormatException($"livello {id} inesistente");
                    Array.Clear(clearLayer.Pixels);
                    break;
                default:
                    throw new FormatException($"azione livello non riconosciuta: {action}");
            }

            UpdateLayerLabel();
            RequestRender();
        }

        private void DrawLine(int x0, int y0, int x1, int y1)
        {
            int dx = x1 - x0;
            int dy = y1 - y0;
            int steps = Math.Max(Math.Abs(dx), Math.Abs(dy));
            if (steps == 0)
            {
                DrawCircle(x0, y0);
                return;
            }

            for (int step = 0; step <= steps; step++)
            {
                double t = (double)step / steps;
                DrawCircle(
                    (int)Math.Round(x0 + dx * t),
                    (int)Math.Round(y0 + dy * t));
            }
        }

        private void DrawCircle(int centerX, int centerY)
        {
            int radiusSquared = _strokeRadius * _strokeRadius;
            for (int y = Math.Max(0, centerY - _strokeRadius); y <= Math.Min(SurfaceHeight - 1, centerY + _strokeRadius); y++)
            {
                for (int x = Math.Max(0, centerX - _strokeRadius); x <= Math.Min(SurfaceWidth - 1, centerX + _strokeRadius); x++)
                {
                    int dx = x - centerX;
                    int dy = y - centerY;
                    if (dx * dx + dy * dy > radiusSquared) continue;
                    int index = y * SurfaceWidth + x;
                    _activeLayer.Pixels[index] = _erasing
                        ? 0
                        : Blend(_activeLayer.Pixels[index], _strokeColor);
                }
            }
        }

        private void ClearAllLayers()
        {
            foreach (DrawingLayer layer in _layers) Array.Clear(layer.Pixels);
            RequestRender();
        }

        private void ComposePixels()
        {
            Array.Fill(_compositePixels, White);
            foreach (DrawingLayer layer in _layers.Where(layer => layer.Visible))
            {
                for (int i = 0; i < _compositePixels.Length; i++)
                {
                    if ((layer.Pixels[i] >>> 24) != 0)
                        _compositePixels[i] = Blend(_compositePixels[i], layer.Pixels[i]);
                }
            }
        }

        private void ComposeAndRender()
        {
            ComposePixels();
            _displayBitmap.WritePixels(
                new Int32Rect(0, 0, SurfaceWidth, SurfaceHeight),
                _compositePixels,
                SurfaceWidth * 4,
                0);
        }

        private static int Blend(int destination, int source)
        {
            int alpha = source >>> 24;
            if (alpha == 255) return source;
            if (alpha == 0) return destination;
            int inverse = 255 - alpha;
            int r = (((source >> 16) & 255) * alpha + ((destination >> 16) & 255) * inverse) / 255;
            int g = (((source >> 8) & 255) * alpha + ((destination >> 8) & 255) * inverse) / 255;
            int b = ((source & 255) * alpha + (destination & 255) * inverse) / 255;
            return unchecked((int)0xFF000000) | (r << 16) | (g << 8) | b;
        }

        private static double ParseNumber(string value)
        {
            value = value.Trim();
            if (double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out double result) ||
                double.TryParse(value, NumberStyles.Float, CultureInfo.CurrentCulture, out result) ||
                double.TryParse(value.Replace(',', '.'), NumberStyles.Float, CultureInfo.InvariantCulture, out result))
            {
                if (double.IsFinite(result)) return result;
            }
            throw new FormatException($"numero non valido: {value}");
        }

        private static int ParseColor(string value)
        {
            if (long.TryParse(value.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out long color))
                return unchecked((int)color);
            throw new FormatException($"colore non valido: {value}");
        }

        private static (int X, int Y) ParsePoint(string xValue, string yValue)
        {
            double nx = Math.Clamp(ParseNumber(xValue), 0, 1);
            double ny = Math.Clamp(ParseNumber(yValue), 0, 1);
            return (
                Math.Clamp((int)Math.Round(nx * (SurfaceWidth - 1)), 0, SurfaceWidth - 1),
                Math.Clamp((int)Math.Round(ny * (SurfaceHeight - 1)), 0, SurfaceHeight - 1));
        }

        private void RequestRender() => _renderPending = true;

        private void UpdateLayerLabel()
        {
            LayerText.Text = $"Livello: {_activeLayer.Name} ({_layers.Count})";
        }

        private void BtnClear_Click(object sender, RoutedEventArgs e) => ClearAllLayers();

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            var dialog = new SaveFileDialog
            {
                FileName = $"DrawTablet_{DateTime.Now:yyyyMMdd_HHmmss}",
                DefaultExt = ".png",
                Filter = "PNG Image|*.png"
            };
            if (dialog.ShowDialog() != true) return;

            try
            {
                ComposeAndRender();
                var encoder = new PngBitmapEncoder();
                encoder.Frames.Add(BitmapFrame.Create(_displayBitmap));
                using var stream = new FileStream(dialog.FileName, FileMode.Create);
                encoder.Save(stream);
                MessageBox.Show("Immagine salvata!", "Successo", MessageBoxButton.OK, MessageBoxImage.Information);
            }
            catch (Exception ex)
            {
                MessageBox.Show($"Errore: {ex.Message}", "Errore", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
        {
            _running = false;
            _renderTimer.Stop();
            _server?.Stop();
        }
    }
}
