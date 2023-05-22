package mahomaps.screens;

import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.TextBox;

import mahomaps.MahoMapsApp;
import mahomaps.Settings;
import mahomaps.map.GeoUpdateThread;
import mahomaps.map.Geopoint;
import mahomaps.map.Line;
import mahomaps.map.MapState;
import mahomaps.map.TileCache;
import mahomaps.map.TileId;
import mahomaps.map.TilesProvider;
import mahomaps.overlays.MapOverlay;
import mahomaps.overlays.NoApiTokenOverlay;
import mahomaps.overlays.SelectOverlay;
import mahomaps.overlays.TileCacheForbiddenOverlay;
import mahomaps.overlays.TileDownloadForbiddenOverlay;
import mahomaps.ui.ControlButtonsContainer;
import mahomaps.ui.UIElement;

public class MapCanvas extends MultitouchCanvas implements CommandListener, Runnable {

	public final int buttonSize = 50;
	public final int buttonMargin = 10;
	public final static int OVERLAYS_SPACING = 11;

	private TilesProvider tiles;

	String[] buttons = new String[] { "geo", "-", "+", "menu" };

	public GeoUpdateThread geo = null;

	// STATE
	public volatile MapState state = MapState.Default();
	int startPx, startPy;
	int lastPx, lastPy;
	boolean dragActive;
	private final Vector overlays = new Vector();
	public Line line;
	private int overlaysH;
	public final Geopoint geolocation;
	public final ControlButtonsContainer controls;
	private boolean touch = hasPointerEvents();
	private boolean mapFocused = true;
	private final Image dummyBuffer = Image.createImage(1, 1);
	private boolean repaintDebugTick = true;
	private int repeatCount = 0;
	public boolean hidden = false;

	private Command back = new Command("Назад", Command.BACK, 0);
	private Command search = new Command("Поиск", Command.OK, 1);
	private TextBox searchBox = new TextBox("Поиск", "", 100, 0);

	private Object repaintLock = new Object();
	private boolean repaint;

	public MapCanvas(TilesProvider tiles) {
		this.tiles = tiles;
		setFullScreenMode(true);
		geolocation = new Geopoint(0, 0);
		geolocation.type = Geopoint.LOCATION;

		searchBox.addCommand(back);
		searchBox.addCommand(search);
		searchBox.setCommandListener(this);

		controls = new ControlButtonsContainer(this);

		CheckApiAcsess();
		if (Settings.cacheMode == Settings.CACHE_DISABLED)
			PushOverlay(new TileCacheForbiddenOverlay());
		if (!Settings.allowDownload)
			PushOverlay(new TileDownloadForbiddenOverlay());
	}
	
	// repaint logic
	
	public void run() {
		try {
			while(true) {
				while(MahoMapsApp.paused || MahoMapsApp.display.getCurrent() != this) {
					synchronized (repaintLock) {
						repaintLock.wait(500);
					}
				}
				if(!dragActive) { 
					repaint = false;
					_update();
					if(repaint) continue;
					synchronized (repaintLock) {
						repaintLock.wait(500);
					}
					continue;
				}
				long repaintTime = System.currentTimeMillis();
				_update();
				repaintTime = 30 - System.currentTimeMillis() - repaintTime;
				if(repaintTime > 0) Thread.sleep(repaintTime);
			}
		} catch (InterruptedException e) {
			throw new RuntimeException("interrupt");
		}
	}
	
	public void requestRepaint() {
		repaint = true;
		synchronized (repaintLock) {
			repaintLock.notify();
		}
	}

	/**
	 * Проверяет есть ли доступ к апи. Выводит окно предупреждения.
	 * 
	 * @return False если нету.
	 */
	public boolean CheckApiAcsess() {
		if (MahoMapsApp.api.token == null) {
			PushOverlay(new NoApiTokenOverlay());
			return false;
		}
		return true;
	}

	public void CloseOverlay(MapOverlay o) {
		CloseOverlay(o.GetId());
	}

	public void CloseOverlay(String id) {
		synchronized (overlays) {
			for (int i = overlays.size() - 1; i >= 0; i--) {
				if (((MapOverlay) overlays.elementAt(i)).GetId().equals(id))
					overlays.removeElementAt(i);
			}
			RecalcOverlaysHeight();
		}
		requestRepaint();
	}

	public void PushOverlay(MapOverlay o) {
		synchronized (overlays) {
			CloseOverlay(o.GetId());
			// Рисуем в никуда для пересчёта макета (для обхода 1-кадрового мигания)
			o.Paint(dummyBuffer.getGraphics(), 0, 0, getWidth(), getHeight());
			overlays.addElement(o);

			RecalcOverlaysHeight();
		}
	}

	private void RecalcOverlaysHeight() {
		int oh = 0;
		for (int i = 0; i < overlays.size(); i++) {
			MapOverlay mo = (MapOverlay) overlays.elementAt(i);
			oh += mo.H + OVERLAYS_SPACING;
		}

		overlaysH = oh;
	}

	public void InvalidateOverlayHeight(MapOverlay o) {
		synchronized (overlays) {
			o.Paint(dummyBuffer.getGraphics(), 0, 0, getWidth(), getHeight());
			RecalcOverlaysHeight();
		}
		requestRepaint();
	}

	public MapOverlay GetOverlay(String id) {
		synchronized (overlays) {
			for (int i = overlays.size() - 1; i >= 0; i--) {
				MapOverlay mo = (MapOverlay) overlays.elementAt(i);
				if (mo.GetId().equals(id))
					return mo;
			}
			return null;
		}
	}

	public Geopoint GetSearchAnchor() {
		if (geo != null && geo.DrawPoint()) {
			return geolocation;
		}
		Geopoint p = GetAtCoords(0, 0);
		if (p.lat > 80d)
			p.lat = 80d;
		if (p.lat < -80d)
			p.lat = -80d;
		return p;
	}

	/**
	 * Получает точку на экране по координатам экрана.
	 *
	 * @param x X относительно центра (центр = 0)
	 * @param y Y относительно центра (центр = 0)
	 * @return Точка.
	 */
	public Geopoint GetAtCoords(int x, int y) {
		MapState ms = state.Clone();
		int tilesCount = 1 << ms.zoom;
		double dx = x;
		dx -= ms.xOffset;
		dx /= 256;
		dx += ms.tileX;
		dx *= 360d;
		dx /= tilesCount;
		double lon = dx - 180d;

		Geopoint g = new Geopoint(0, lon);
		double step = 60d;
		while (true) {
			double or = g.lat;
			if (Math.abs(or) > 91d) {
				g.lat = or > 0 ? 90 : -90;
				return g;
			}
			int zero = Math.abs(g.GetScreenY(ms) - y);
			if (zero <= 2)
				break;
			g.lat = or + step;
			int plus = Math.abs(g.GetScreenY(ms) - y);
			g.lat = or - step;
			int minus = Math.abs(g.GetScreenY(ms) - y);

			if (zero < Math.min(minus, plus)) {
				g.lat = or;
			} else if (minus < plus) {
				g.lat = or - step;
			} else {
				g.lat = or + step;
			}
			step /= 2d;
			if (step < 0.000001d) {
				System.out.println("Step too small, bumping");
				step = 0.05d;
			}
		}

		return g;
	}

	// DRAW SECTION

	private void repaint(Graphics g) {
		int w = getWidth();
		int h = getHeight();
		g.setGrayScale(127);
		g.fillRect(0, 0, w, h);
		drawMap(g, w, h);
		drawOverlay(g, w, h);
		UIElement.CommitInputQueue();
		if (UIElement.IsQueueEmpty() && !touch) {
			mapFocused = true;
			UIElement.Deselect();
		}
	}

	private void drawMap(Graphics g, int w, int h) {
		MapState ms = state;
		tiles.BeginMapPaint();
		g.translate(w >> 1, h >> 1);

		int trX = 1;
		while (trX * 256 < (w >> 1))
			trX++;
		int trY = 1;
		while (trY * 256 < (h >> 1))
			trY++;

		// кэширование для защиты от подмены переключенным потоком
		final int xo = ms.xOffset;
		final int tx = ms.tileX;

		int y = ms.yOffset - trY * 256;
		int yi = ms.tileY - trY;
		while (y < h / 2) {
			int x = xo - trX * 256;
			int xi = tx - trX;
			while (x < w / 2) {
				TileCache tile = tiles.getTile(new TileId(xi, yi, ms.zoom));
				if (tile != null)
					tile.paint(g, x, y);
				x += 256;
				xi++;
			}
			y += 256;
			yi++;
		}

		Line l = line;
		if (l != null)
			l.Draw(g, this);

		if (geo != null && geo.DrawPoint()) {
			geolocation.paint(g, ms);
		}

		for (int i = 0; i < overlays.size(); i++) {
			Vector points = ((MapOverlay) overlays.elementAt(i)).GetPoints();
			int s = points.size();
			for (int j = 0; j < s; j++) {
				((Geopoint) points.elementAt(j)).paint(g, ms);
			}
		}

		g.translate(-(w >> 1), -(h >> 1));
		tiles.EndMapPaint();
	}

	private void drawOverlay(Graphics g, int w, int h) {
		Font f = Font.getFont(0, 0, 8);
		g.setColor(0);
		g.setFont(f);
		if (Settings.drawTileInfo)
			g.drawString(state.toString(), 5, 5, 0);

		controls.info = GetGeoInfo();

		boolean t = touch;
		int fh = f.getHeight();
		if (!t)
			h -= fh;

		int y = h - overlaysH;
		int oh = 0;
		for (int i = 0; i < overlays.size(); i++) {
			MapOverlay mo = (MapOverlay) overlays.elementAt(i);
			mo.Paint(g, 5, y, w - 10, h);
			y += mo.H + OVERLAYS_SPACING;
			oh += mo.H + OVERLAYS_SPACING;
		}

		if (t) {
			controls.Paint(g, 0, 0, w, h - overlaysH);
		}
		controls.PaintInfo(g, 0, 0, w, h - overlaysH);

		overlaysH = oh;

		if (!t) {
			g.setColor(0);
			g.fillRect(0, h, w, fh);
			g.setColor(-1);
			g.setFont(f);

			g.drawString("Меню", 0, y, 0);
			g.drawString("Выбор", w / 2, y, Graphics.TOP | Graphics.HCENTER);
			if (mapFocused) {
				if (!UIElement.IsQueueEmpty())
					g.drawString("К панелям", w, y, Graphics.TOP | Graphics.RIGHT);
			} else {
				g.drawString("К карте", w, y, Graphics.TOP | Graphics.RIGHT);
			}
		}
	}

	private Vector GetGeoInfo() {
		if (geo == null || !Settings.showGeo) {
			return null;
		}

		Vector v = new Vector();

		// статус и время
		int passed = (int) ((System.currentTimeMillis() - geo.lastUpdateTime) / 1000);
		if (geo.state != GeoUpdateThread.STATE_OK) {
			v.addElement(GeoUpdateThread.states[geo.state]);
			v.addElement("Прошло " + passed + " с.");
		} else if (passed >= 5) {
			v.addElement("Не обновлялось: " + passed + " с.");
		}

		// метод и спутники
		{
			String s = geo.method;
			if (geo.totalSattelitesInView >= 0) {
				String s2 = (geo.sattelites >= 0 ? geo.sattelites + "/" : "") + geo.totalSattelitesInView;
				if (s == null) {
					s = "Спутников: " + s2;
				} else {
					s += " (" + s2 + ")";
				}
			}
			if (s != null)
				v.addElement(s);
		}

		// координаты
		if (geo.DrawPoint()) {
			String[] r = geolocation.GetRounded();
			v.addElement(r[0]);
			v.addElement(r[1]);
		}
		return v;
	}

	// LOGIC

	private void _update() {
		long time = System.currentTimeMillis();
		Graphics g = getGraphics();
		repaint(g);
		time = System.currentTimeMillis() - time;
		g.setColor(0);
		g.drawString(time + "ms " + (repaintDebugTick ? "+" : "="), 5, 65, 0);
		flushGraphics();
		repaintDebugTick = !repaintDebugTick;
	}

	public void ShowGeo() {
		if (geo == null) {
			geo = new GeoUpdateThread(geolocation, this);
			geo.start();
		} else if (geo.DrawPoint()) {
			state = MapState.FocusAt(geolocation);
		} else if (geo.state == GeoUpdateThread.STATE_UNAVAILABLE) {
			geo.restart();
		}
		
		requestRepaint();
	}

	// INPUT

	protected void keyPressed(int k) {
		keyPressedInternal(k);
	}

	protected void keyPressedInternal(int k) {
		// "home" button
		if (k == -12)
			return;
		touch = false;
		if (k == -6) {
			MahoMapsApp.BringMenu();
			return;
		}
		if (k == -7) {
			if (mapFocused) {
				if (!UIElement.IsQueueEmpty()) {
					mapFocused = false;
					UIElement.SelectDown();
				}
			} else {
				mapFocused = true;
				UIElement.Deselect();
			}
			return;
		}
		handling: {
		int ga = 0;
		try {
			ga = getGameAction(k);
		} catch (IllegalArgumentException e) { // j2l moment
		}
		if (mapFocused) {
			switch (ga) {
			case FIRE:
				Geopoint s = GetAtCoords(0, 0);
				if (Math.abs(s.lat) <= 85) {
					PushOverlay(new SelectOverlay(s));
				}
				break handling;
			case UP:
				state.yOffset += 10;
				state.ClampOffset();
				break handling;
			case DOWN:
				state.yOffset -= 10;
				state.ClampOffset();
				break handling;
			case LEFT:
				state.xOffset += 10;
				state.ClampOffset();
				break handling;
			case RIGHT:
				state.xOffset -= 10;
				state.ClampOffset();
				break handling;
			}
			switch (k) {
			case KEY_NUM1:
				state = state.ZoomOut();
				break handling;
			case KEY_NUM3:
				state = state.ZoomIn();
				break handling;
			case KEY_NUM7:
				BeginTextSearch();
				break handling;
			case KEY_NUM9:
				ShowGeo();
				break handling;
			}
		} else {
			switch (ga) {
			case FIRE:
				UIElement.TriggerSelected();
				break;
			case UP:
			case LEFT:
				UIElement.SelectUp();
				break;
			case DOWN:
			case RIGHT:
				UIElement.SelectDown();
				break;
			}
		}
		}
		requestRepaint();
	}

	protected void keyRepeated(int k) {
		touch = false;
		int ga = getGameAction(k);
		if (mapFocused) {
			int val;
			if (repeatCount < 25) {
				val = 2 * repeatCount;
			} else {
				val = 50;
			}
			switch (ga) {
			case UP:
				state.yOffset += val;
				break;
			case DOWN:
				state.yOffset -= val;
				break;
			case LEFT:
				state.xOffset += val;
				break;
			case RIGHT:
				state.xOffset -= val;
				break;
			default:
				return;
			}
			state.ClampOffset();
			repeatCount++;
			requestRepaint();
		}
	}

	protected void keyReleased(int k) {
		repeatCount = 0;
		requestRepaint();
	}

	protected void pointerPressed(int x, int y, int n) {
		touch = true;
		mapFocused = true;
		if (n == 0) {
			UIElement.InvokePressEvent(x, y);
			dragActive = false;
			startPx = x;
			startPy = y;
			lastPx = x;
			lastPy = y;
		}
		requestRepaint();
	}

	protected void pointerDragged(int x, int y, int n) {
		if (n == 0) {
			if (!dragActive) {
				if (Math.abs(x - startPx) > 8 || Math.abs(y - startPy) > 8) {
					UIElement.InvokeReleaseEvent();
					dragActive = true;
				} else
					return;
			}
			state.xOffset += x - lastPx;
			state.yOffset += y - lastPy;
			lastPx = x;
			lastPy = y;

			state.ClampOffset();
		}
		requestRepaint();
	}

	protected void pointerReleased(int x, int y, int n) {
		if (n != 0)
			return;
		if (dragActive) {
			dragActive = false;
			return;
		}

		UIElement.InvokeReleaseEvent();
		if (UIElement.InvokeTouchEvent(x, y))
			return;

		if (y > getHeight() - overlaysH)
			return;

		// points

		synchronized (overlays) {
			for (int i = 0; i < overlays.size(); i++) {
				MapOverlay mo = (MapOverlay) overlays.elementAt(i);
				Vector points = mo.GetPoints();
				int s = points.size();
				for (int j = 0; j < s; j++) {
					Geopoint p = (Geopoint) points.elementAt(j);
					if (p.isTouched(this, state, x, y)) {
						if (mo.OnPointTap(p)) {
							requestRepaint();
							return;
						}
					}
				}
			}
		}

		// tap at map
		if (MahoMapsApp.lastSearch == null) {
			Geopoint s = GetAtCoords(x - getWidth() / 2, y - getHeight() / 2);
			if (Math.abs(s.lat) > 85 || Math.abs(s.lon) >= 180) {
				requestRepaint();
				return;
			}
			PushOverlay(new SelectOverlay(s));
		}
		
		requestRepaint();
	}

	public void dispose() {
		if (geo != null)
			geo.Dispose();
	}

	public void BeginTextSearch() {
		if (CheckApiAcsess()) {
			if (MahoMapsApp.lastSearch == null) {
				MahoMapsApp.BringSubScreen(searchBox);
			}
		}
	}

	public void commandAction(Command c, Displayable d) {
		if (d == searchBox) {
			if (c == back) {
				MahoMapsApp.BringMap();
			} else {
				CloseOverlay(SelectOverlay.ID);
				Geopoint sa = GetSearchAnchor();
				MahoMapsApp.BringSubScreen(new SearchScreen(searchBox.getString(), sa));
			}
		}
	}

	protected void sizeChanged(int w, int h) {
		super.sizeChanged(w, h);
		requestRepaint();
	}

	protected void hideNotify() {
		hidden = true;
	}

	protected void showNotify() {
		hidden = false;
		super.showNotify();
		requestRepaint();
	}
}
