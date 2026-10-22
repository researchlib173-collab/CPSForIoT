using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace CpsVideoHost {
    class Program {
        [DllImport("user32.dll", SetLastError = true)]
        static extern IntPtr SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);

        const uint SWP_SHOWWINDOW = 0x0040;
        const uint SWP_NOACTIVATE = 0x0010;

        [STAThread]
        static void Main(string[] args) {
            if (args.Length < 1) {
                Console.WriteLine("Usage: VideoHost.exe <videoPath> [posX] [posY] [width] [height]");
                return;
            }

            string videoPath = Path.GetFullPath(args[0]);
            int posX = 100;
            int posY = 100;
            int width = 720;
            int height = 405;

            if (args.Length > 1) int.TryParse(args[1], out posX);
            if (args.Length > 2) int.TryParse(args[2], out posY);
            if (args.Length > 3) int.TryParse(args[3], out width);
            if (args.Length > 4) int.TryParse(args[4], out height);

            var app = new Application();
            var window = new Window {
                Title = "Timeline 1.mov — Nobita Departure Video Feed",
                Left = posX,
                Top = posY,
                Width = width,
                Height = height,
                Background = new SolidColorBrush(System.Windows.Media.Color.FromRgb(10, 14, 20)),
                WindowStyle = WindowStyle.ToolWindow,
                ResizeMode = ResizeMode.CanResize,
                Topmost = true
            };

            var grid = new Grid();
            window.Content = grid;

            var media = new MediaElement {
                LoadedBehavior = MediaState.Manual,
                UnloadedBehavior = MediaState.Manual,
                Stretch = Stretch.Uniform,
                Source = new Uri(videoPath)
            };
            grid.Children.Add(media);

            media.MediaOpened += (s, e) => {
                double dur = media.NaturalDuration.HasTimeSpan ? media.NaturalDuration.TimeSpan.TotalSeconds : 6.0;
                Console.WriteLine("MEDIA_OPENED:" + dur);
            };

            media.MediaEnded += (s, e) => {
                Console.WriteLine("MEDIA_ENDED");
            };

            media.MediaFailed += (s, e) => {
                Console.WriteLine("MEDIA_FAILED:" + e.ErrorException.Message);
            };

            // Background IPC console command listener loop
            var cmdThread = new System.Threading.Thread(() => {
                string line;
                while ((line = Console.ReadLine()) != null) {
                    string cmd = line.Trim();
                    if (cmd.Length == 0) continue;
                    window.Dispatcher.Invoke(() => {
                        try {
                            if (cmd.Equals("PLAY", StringComparison.OrdinalIgnoreCase)) {
                                media.Play();
                                Console.WriteLine("ACK_PLAY");
                            } else if (cmd.Equals("PAUSE", StringComparison.OrdinalIgnoreCase)) {
                                media.Pause();
                                Console.WriteLine("ACK_PAUSE");
                            } else if (cmd.Equals("STOP", StringComparison.OrdinalIgnoreCase)) {
                                media.Stop();
                                Console.WriteLine("ACK_STOP");
                            } else if (cmd.StartsWith("SEEK:", StringComparison.OrdinalIgnoreCase)) {
                                double sec = double.Parse(cmd.Substring(5), System.Globalization.CultureInfo.InvariantCulture);
                                media.Position = TimeSpan.FromSeconds(sec);
                                Console.WriteLine("ACK_SEEK:" + sec);
                            } else if (cmd.StartsWith("MOVE:", StringComparison.OrdinalIgnoreCase)) {
                                // MOVE:x,y,w,h
                                string[] parts = cmd.Substring(5).Split(',');
                                int mx = int.Parse(parts[0]);
                                int my = int.Parse(parts[1]);
                                int mw = int.Parse(parts[2]);
                                int mh = int.Parse(parts[3]);
                                window.Left = mx;
                                window.Top = my;
                                window.Width = mw;
                                window.Height = mh;
                                Console.WriteLine("ACK_MOVE");
                            } else if (cmd.Equals("GET_POS", StringComparison.OrdinalIgnoreCase)) {
                                Console.WriteLine("POS:" + media.Position.TotalSeconds);
                            } else if (cmd.Equals("QUIT", StringComparison.OrdinalIgnoreCase)) {
                                window.Close();
                                app.Shutdown();
                            }
                        } catch (Exception ex) {
                            Console.WriteLine("ERR:" + ex.Message);
                        }
                    });
                }
            }) { IsBackground = true };
            cmdThread.Start();

            app.Run(window);
        }
    }
}
