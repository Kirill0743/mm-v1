package mahomaps.map;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

public class TileCache extends TileId {

	public final Image img;
	public volatile int state;
	public int unuseCount;

	public TileCache(int x, int y, int zoom, Image img) {
		super(x, y, zoom);
		this.img = img;
	}

	public TileCache(TileId id, Image img) {
		super(id.x, id.y, id.zoom);
		this.img = img;
	}

	public boolean is(TileId id) {
		return id.x == x && id.y == y && id.zoom == zoom;
	}

	public void paint(Graphics g, int tx, int ty) {
		Font f = Font.getFont(0, 0, 8);
		int vo = f.getHeight();
		g.setFont(f);
		if (state == STATE_READY) {
			g.drawImage(img, tx, ty, 0);
			g.setColor(0, 0, 255);
		} else if (state == STATE_LOADING) {
			g.setColor(0, 127, 0);
			g.drawString("Загружаем...", tx + 128, ty + 128 - vo, Graphics.TOP | Graphics.HCENTER);
		} else if(state == STATE_ERROR) {
			g.setColor(255, 0, 0);
			g.drawString("Ошибка загрузки", tx + 128, ty + 128 - vo, Graphics.TOP | Graphics.HCENTER);
		} else {
			g.setGrayScale(0);
			g.drawString("В очереди", tx + 128, ty + 128 - vo, Graphics.TOP | Graphics.HCENTER);
		}
		g.drawRect(tx, ty, 255, 255);
		g.fillRect(tx + 125, ty + 127, 6, 2);
		g.fillRect(tx + 127, ty + 125, 2, 6);
		g.drawString("tile " + this.x + " " + this.y, tx + 1, ty + 1, 0);
	}

	public final int STATE_PENDING = 0;
	public final int STATE_LOADING = 1;
	public final int STATE_READY = 0;
	public final int STATE_ERROR = 3;
}
