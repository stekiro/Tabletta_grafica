using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using Microsoft.Win32;

namespace DrawTabletPC
{
    public partial class MainWindow : Window
    {
        private TcpListener? _server;
        private Thread? _serverThread;
        private volatile bool _running = false;

        // Current drawing state
        private Polyline? _currentPolyline;
        private double _startX, _startY;
        private string _currentTool = "PENCIL";
        private Color _currentColor = Colors.Black;
        private double _strokeWidth = 4;

        public MainWindow()
        {
            InitializeComponent();
            Loaded += OnLoaded;
            Closing += OnClosing;
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            // Show local IP
            string ip = GetLocalIP();
            IpText.Text = $"IP: {ip} | Porta: 9999";
            InfoText.Text = $"Inserisci nell'app Android → IP: {ip}";

            StartServer();
        }

        private string GetLocalIP()
        {
            try
            {
                using var socket = new System.Net.Sockets.Socket(
                    AddressFamily.InterNetwork, SocketType.Dgram, 0);
                socket.Connect("8.8.8.8", 65530);
                return (socket.LocalEndPoint as IPEndPoint)?.Address.ToString() ?? "127.0.0.1";
            }
            catch { return "127.0.0.1"; }
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
                            var client = _server.AcceptTcpClient();
                            Dispatcher.Invoke(() =>
                            {
                                StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0x43, 0xA0, 0x47));
                                StatusText.Text = "✅ Tablet connesso";
                            });
                            HandleClient(client);
                        }
                        catch (SocketException) { if (!_running) break; }
                    }
                }
                catch (Exception ex)
                {
                    Dispatcher.Invoke(() => StatusText.Text = $"Errore: {ex.Message}");
                }
            });
            _serverThread.IsBackground = true;
            _serverThread.Start();
        }

        private void HandleClient(TcpClient client)
        {
            using var reader = new StreamReader(client.GetStream());
            try
            {
                string? line;
                while ((line = reader.ReadLine()) != null)
                {
                    ProcessCommand(line);
                }
            }
            catch { }
            finally
            {
                client.Close();
                Dispatcher.Invoke(() =>
                {
                    StatusDot.Fill = new SolidColorBrush(Color.FromRgb(0xEF, 0x53, 0x50));
                    StatusText.Text = "Tablet disconnesso. In attesa...";
                });
            }
        }

        private void ProcessCommand(string line)
        {
            var parts = line.Split('|');
            if (parts.Length == 0) return;

            Dispatcher.Invoke(() =>
            {
                switch (parts[0])
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
                        DrawCanvas.Children.Clear();
                        break;
                    case "FILL" when parts.Length >= 4:
                        HandleFill(parts);
                        break;
                }
            });
        }

        private void HandleDown(string[] parts)
        {
            double nx = double.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture);
            double ny = double.Parse(parts[2], System.Globalization.CultureInfo.InvariantCulture);
            int colorInt = int.Parse(parts[3]);
            double stroke = double.Parse(parts[4], System.Globalization.CultureInfo.InvariantCulture);
            _currentTool = parts[5];

            _currentColor = IntToColor(colorInt);
            _strokeWidth = Math.Max(stroke * (DrawCanvas.ActualWidth / 1080.0), 1.0);

            double x = nx * DrawCanvas.ActualWidth;
            double y = ny * DrawCanvas.ActualHeight;
            _startX = x; _startY = y;

            if (_currentTool == "ERASER")
            {
                _currentPolyline = new Polyline
                {
                    Stroke = Brushes.White,
                    StrokeThickness = _strokeWidth * 3,
                    StrokeLineJoin = PenLineJoin.Round,
                    StrokeStartLineCap = PenLineCap.Round,
                    StrokeEndLineCap = PenLineCap.Round
                };
            }
            else
            {
                var brush = new SolidColorBrush(_currentColor);
                if (_currentTool == "BRUSH")
                {
                    brush.Opacity = 0.7;
                    _strokeWidth *= 2;
                }
                _currentPolyline = new Polyline
                {
                    Stroke = brush,
                    StrokeThickness = _strokeWidth,
                    StrokeLineJoin = PenLineJoin.Round,
                    StrokeStartLineCap = PenLineCap.Round,
                    StrokeEndLineCap = PenLineCap.Round
                };
            }

            _currentPolyline.Points.Add(new Point(x, y));
            DrawCanvas.Children.Add(_currentPolyline);
        }

        private void HandleMove(string[] parts)
        {
            if (_currentPolyline == null) return;
            double nx = double.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture);
            double ny = double.Parse(parts[2], System.Globalization.CultureInfo.InvariantCulture);
            double x = nx * DrawCanvas.ActualWidth;
            double y = ny * DrawCanvas.ActualHeight;
            _currentPolyline.Points.Add(new Point(x, y));
        }

        private void HandleUp(string[] parts)
        {
            if (_currentPolyline == null) return;
            double nx = double.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture);
            double ny = double.Parse(parts[2], System.Globalization.CultureInfo.InvariantCulture);
            double x = nx * DrawCanvas.ActualWidth;
            double y = ny * DrawCanvas.ActualHeight;
            _currentPolyline.Points.Add(new Point(x, y));
            _currentPolyline = null;
        }

        private void HandleFill(string[] parts)
        {
            // Approximate fill: draw a colored rectangle over canvas
            // Real flood-fill on WPF Canvas would require bitmap manipulation
            double nx = double.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture);
            double ny = double.Parse(parts[2], System.Globalization.CultureInfo.InvariantCulture);
            int colorInt = int.Parse(parts[3]);
            var color = IntToColor(colorInt);

            // Simple approximation: add a large filled ellipse at position
            var ellipse = new Ellipse
            {
                Width = DrawCanvas.ActualWidth * 0.4,
                Height = DrawCanvas.ActualHeight * 0.4,
                Fill = new SolidColorBrush(color),
                Opacity = 0.85
            };
            double x = nx * DrawCanvas.ActualWidth - ellipse.Width / 2;
            double y = ny * DrawCanvas.ActualHeight - ellipse.Height / 2;
            Canvas.SetLeft(ellipse, x);
            Canvas.SetTop(ellipse, y);
            DrawCanvas.Children.Add(ellipse);
        }

        private Color IntToColor(int argb)
        {
            byte a = (byte)((argb >> 24) & 0xFF);
            byte r = (byte)((argb >> 16) & 0xFF);
            byte g = (byte)((argb >> 8) & 0xFF);
            byte b = (byte)(argb & 0xFF);
            return Color.FromArgb(a == 0 ? (byte)255 : a, r, g, b);
        }

        private void BtnClear_Click(object sender, RoutedEventArgs e)
        {
            DrawCanvas.Children.Clear();
        }

        private void BtnSave_Click(object sender, RoutedEventArgs e)
        {
            var dlg = new SaveFileDialog
            {
                FileName = $"DrawTablet_{DateTime.Now:yyyyMMdd_HHmmss}",
                DefaultExt = ".png",
                Filter = "PNG Image|*.png"
            };
            if (dlg.ShowDialog() == true)
            {
                try
                {
                    var rtb = new RenderTargetBitmap(
                        (int)DrawCanvas.ActualWidth,
                        (int)DrawCanvas.ActualHeight,
                        96, 96, PixelFormats.Pbgra32);
                    rtb.Render(DrawCanvas);
                    var encoder = new PngBitmapEncoder();
                    encoder.Frames.Add(BitmapFrame.Create(rtb));
                    using var fs = new FileStream(dlg.FileName, FileMode.Create);
                    encoder.Save(fs);
                    MessageBox.Show("Immagine salvata!", "Successo",
                        MessageBoxButton.OK, MessageBoxImage.Information);
                }
                catch (Exception ex)
                {
                    MessageBox.Show($"Errore: {ex.Message}", "Errore",
                        MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }

        private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
        {
            _running = false;
            _server?.Stop();
        }
    }
}
