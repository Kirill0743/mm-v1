package mahomaps.map;

import mahomaps.Settings;

public class MapState {
	public int tileX, tileY;
	public int xOffset, yOffset;
	public int zoom;

	public final void ClampOffset() {
		while (xOffset > 0) {
			tileX--;
			xOffset -= 256;
		}
		while (yOffset > 0) {
			tileY--;
			yOffset -= 256;
		}
		while (xOffset < -255) {
			tileX++;
			xOffset += 256;
		}
		while (yOffset < -255) {
			tileY++;
			yOffset += 256;
		}
	}

	public MapState Clone() {
		MapState ms = new MapState();
		ms.tileX = tileX;
		ms.tileY = tileY;
		ms.xOffset = xOffset;
		ms.yOffset = yOffset;
		ms.zoom = zoom;
		return ms;
	}

	public MapState ZoomIn() {
		if (zoom >= 18)
			return Clone();
		MapState ms = new MapState();
		ms.zoom = zoom + 1;
		ms.tileX = tileX * 2;
		ms.tileY = tileY * 2;
		ms.xOffset = xOffset * 2;
		ms.yOffset = yOffset * 2;
		ms.ClampOffset();
		return ms;
	}

	public MapState ZoomOut() {
		if (zoom <= 0)
			return Clone();
		MapState ms = new MapState();
		ms.zoom = zoom - 1;
		ms.tileX = tileX / 2;
		ms.tileY = tileY / 2;
		ms.xOffset = (xOffset - (tileX % 2 == 1 ? 256 : 0)) / 2;
		ms.yOffset = (yOffset - (tileY % 2 == 1 ? 256 : 0)) / 2;
		ms.ClampOffset();
		return ms;
	}

	public static MapState FocusAt(Geopoint p) {
		MapState ms = new MapState();
		ms.zoom = Settings.focusZoom;
		ms.xOffset -= p.GetScreenX(ms);
		ms.yOffset -= p.GetScreenY(ms);
		ms.ClampOffset();
		return ms;
	}

	public static MapState Default() {
		MapState ms = new MapState();
		ms.zoom = 0;
		ms.xOffset = -128;
		ms.yOffset = -128;
		return ms;
	}
}