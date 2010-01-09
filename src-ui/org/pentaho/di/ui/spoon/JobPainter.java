package org.pentaho.di.ui.spoon;

import java.util.List;
import java.util.Map;

import org.pentaho.di.core.NotePadMeta;
import org.pentaho.di.core.gui.AreaOwner;
import org.pentaho.di.core.gui.BasePainter;
import org.pentaho.di.core.gui.GCInterface;
import org.pentaho.di.core.gui.Point;
import org.pentaho.di.core.gui.Rectangle;
import org.pentaho.di.core.gui.ScrollBarInterface;
import org.pentaho.di.core.gui.AreaOwner.AreaType;
import org.pentaho.di.core.gui.GCInterface.EColor;
import org.pentaho.di.core.gui.GCInterface.EFont;
import org.pentaho.di.core.gui.GCInterface.EImage;
import org.pentaho.di.core.gui.GCInterface.ELineStyle;
import org.pentaho.di.job.JobHopMeta;
import org.pentaho.di.job.JobMeta;
import org.pentaho.di.job.entry.JobEntryCopy;

public class JobPainter extends BasePainter {

	private JobMeta						jobMeta;
	private JobHopMeta					candidate;

	private List<JobEntryCopy>			mouseOverEntries;
	private Map<JobEntryCopy, String>	entryLogMap;
	private JobEntryCopy	startHopEntry;
	private Point	endHopLocation;
	private JobEntryCopy	endHopEntry;
	private JobEntryCopy	noInputEntry;

	public JobPainter(GCInterface gc, JobMeta jobMeta, Point area, ScrollBarInterface hori, ScrollBarInterface vert, JobHopMeta candidate, Point drop_candidate, Rectangle selrect, List<AreaOwner> areaOwners, List<JobEntryCopy> mouseOverEntries,
			int iconsize, int linewidth, int gridsize, int shadowSize, boolean antiAliasing, 
    		String noteFontName, int noteFontHeight) {
		super(gc, jobMeta, area, hori, vert, drop_candidate, selrect, areaOwners,
				iconsize, linewidth, gridsize, shadowSize, antiAliasing, 
        		noteFontName, noteFontHeight
			);
		this.jobMeta = jobMeta;

		this.candidate = candidate;

		this.mouseOverEntries = mouseOverEntries;

		entryLogMap = null;
	}

	public void drawJob() {

		Point max = jobMeta.getMaximum();
		Point thumb = getThumb(area, max);
		offset = getOffset(thumb, area);

		gc.setBackground(EColor.BACKGROUND);

		hori.setThumb(thumb.x);
		vert.setThumb(thumb.y);

		// If there is a shadow, we draw the transformation first with an alpha
		// setting
		//
		if (shadowsize > 0) {
			gc.setAlpha(20);
			gc.setTransform(translationX, translationY, shadowSize, magnification);
			shadow = true;
			drawJobElements();
		}

		// Draw the transformation onto the image
		//
		gc.setAlpha(255);
		gc.setTransform(translationX, translationY, 0, magnification);

		shadow = false;
		drawJobElements();

	}

	private void drawJobElements() {
		if (!shadow && gridSize > 1) {
			drawGrid();
		}

		// First draw the notes...
		gc.setFont(EFont.NOTE);

		for (int i = 0; i < jobMeta.nrNotes(); i++) {
			NotePadMeta ni = jobMeta.getNote(i);
			drawNote(ni);
		}

		gc.setFont(EFont.GRAPH);

		// ... and then the rest on top of it...
		for (int i = 0; i < jobMeta.nrJobHops(); i++) {
			JobHopMeta hi = jobMeta.getJobHop(i);
			drawJobHop(hi, false);
		}

		if (candidate != null) {
			drawJobHop(candidate, true);
		} else {
			if (startHopEntry != null && endHopLocation != null) {
				Point fr = startHopEntry.getLocation();
				Point to = endHopLocation;
				if (endHopEntry == null) {
					gc.setForeground(EColor.GRAY);
				} else {
					gc.setForeground(EColor.BLUE);
				}
				drawArrow(fr.x + iconsize / 2, fr.y + iconsize / 2, to.x, to.y, theta, calcArrowLength(), 1.2, null, startHopEntry, endHopEntry == null ? endHopLocation : endHopEntry);
			} else if (endHopEntry != null && endHopLocation != null) {
				Point fr = endHopLocation;
				Point to = endHopEntry.getLocation();
				if (startHopEntry == null) {
					gc.setForeground(EColor.GRAY);
				} else {
					gc.setForeground(EColor.BLUE);
				}
				drawArrow(fr.x, fr.y, to.x + iconsize / 2, to.y + iconsize / 2, theta, calcArrowLength(), 1.2, null, startHopEntry == null ? endHopLocation : startHopEntry, endHopEntry);
			}
		}		

		for (int j = 0; j < jobMeta.nrJobEntries(); j++) {
			JobEntryCopy je = jobMeta.getJobEntry(j);
			drawJobEntryCopy(je);
		}
		
        // Display an icon on the indicated location signaling to the user that the step in question does not accept input 
        //
        if (noInputEntry!=null) {
        	gc.setLineWidth(2);	
        	gc.setForeground(EColor.RED);
        	Point n = noInputEntry.getLocation();
        	gc.drawLine(n.x-5, n.y-5, n.x+iconsize+10, n.y+iconsize+10);
        	gc.drawLine(n.x-5, n.y+iconsize+5, n.x+iconsize+5, n.y-5);
        }


		if (drop_candidate != null) {
			gc.setLineStyle(ELineStyle.SOLID);
			gc.setForeground(EColor.BLACK);
			Point screen = real2screen(drop_candidate.x, drop_candidate.y, offset);
			gc.drawRectangle(screen.x, screen.y, iconsize, iconsize);
		}

		if (!shadow) {
			drawRect(selrect);
		}
	}

	protected void drawJobEntryCopy(JobEntryCopy jobEntryCopy) {
		if (!jobEntryCopy.isDrawn())
			return;

		Point pt = jobEntryCopy.getLocation();

		int x, y;
		if (pt != null) {
			x = pt.x;
			y = pt.y;
		} else {
			x = 50;
			y = 50;
		}
		String name = jobEntryCopy.getName();
		if (jobEntryCopy.isSelected())
			gc.setLineWidth(3);
		else
			gc.setLineWidth(1);

		gc.drawJobEntryIcon(x, y, jobEntryCopy);
		gc.setBackground(EColor.BACKGROUND);
		gc.drawRectangle(offset.x + x - 1, offset.y + y - 1, iconsize + 1, iconsize + 1);
		Point textsize = new Point(gc.textExtent("" + name).x, gc.textExtent("" + name).y);

		if (!shadow) {
			areaOwners.add(new AreaOwner(AreaType.JOB_ENTRY_ICON, x, y, iconsize, iconsize, subject, jobEntryCopy));
		}
		
		gc.setBackground(EColor.BACKGROUND);
		gc.setLineWidth(1);

		int xpos = offset.x + x + (iconsize / 2) - (textsize.x / 2);
		int ypos = offset.y + y + iconsize + 5;

		gc.setForeground(EColor.BLACK);
		gc.drawText(name, xpos, ypos, true);
		
		
        // Optionally drawn the mouse-over information
        //
        if (mouseOverEntries.contains(jobEntryCopy)) {
        	EImage[] miniIcons = new EImage[] { EImage.INPUT, EImage.EDIT, EImage.CONTEXT_MENU, EImage.OUTPUT, };
        	
        	// First drawn the mini-icons balloon below the job entry
        	//
        	int totalHeight=0;
        	int totalIconsWidth=0;
        	int totalWidth=2*MINI_ICON_MARGIN;
        	for (EImage miniIcon : miniIcons) {
        		Point bounds = gc.getImageBounds(miniIcon);
        		totalWidth+=bounds.x+MINI_ICON_MARGIN;
        		totalIconsWidth+=bounds.x+MINI_ICON_MARGIN;
        		if (bounds.y>totalHeight) totalHeight=bounds.y;
        	}
        	totalHeight+=2*MINI_ICON_MARGIN;
        	        	
        	gc.setFont(EFont.SMALL);
        	String trimmedName = jobEntryCopy.getName().length()<30 ? jobEntryCopy.getName() : jobEntryCopy.getName().substring(0,30);
        	Point nameExtent = gc.textExtent(trimmedName);
        	nameExtent.y+=2*MINI_ICON_MARGIN;
        	nameExtent.x+=3*MINI_ICON_MARGIN;
        	totalHeight+=nameExtent.y;
        	if (nameExtent.x>totalWidth) totalWidth=nameExtent.x;

        	int areaX = x+iconsize/2-totalWidth/2+MINI_ICON_SKEW;
        	int areaY = y+iconsize+MINI_ICON_DISTANCE;

        	gc.setForeground(EColor.DARKGRAY);
        	gc.setBackground(EColor.LIGHTGRAY);
        	gc.setLineWidth(1);
        	gc.fillRoundRectangle(areaX, areaY, totalWidth, totalHeight, 7, 7);
        	gc.drawRoundRectangle(areaX, areaY, totalWidth, totalHeight, 7, 7);

        	gc.setBackground(EColor.BACKGROUND);
        	gc.fillRoundRectangle(areaX+2, areaY+2, totalWidth-MINI_ICON_MARGIN+1, nameExtent.y-MINI_ICON_MARGIN, 7, 7);
        	gc.setForeground(EColor.BLACK);
        	gc.drawText(trimmedName, areaX+(totalWidth-nameExtent.x)/2+MINI_ICON_MARGIN, areaY+MINI_ICON_MARGIN, true);
        	gc.setForeground(EColor.DARKGRAY);
        	gc.setBackground(EColor.LIGHTGRAY);

        	gc.setFont(EFont.GRAPH);
        	areaOwners.add(new AreaOwner(AreaType.MINI_ICONS_BALLOON, areaX, areaY, totalWidth, totalHeight, jobMeta, jobEntryCopy));
        	
        	gc.fillPolygon(new int[] { areaX+totalWidth/2-MINI_ICON_TRIANGLE_BASE/2+1, areaY+2, areaX+totalWidth/2+MINI_ICON_TRIANGLE_BASE/2, areaY+2, areaX+totalWidth/2-MINI_ICON_SKEW, areaY-MINI_ICON_DISTANCE-5, });
        	gc.drawPolyline(new int[] { areaX+totalWidth/2-MINI_ICON_TRIANGLE_BASE/2+1, areaY, areaX+totalWidth/2-MINI_ICON_SKEW, areaY-MINI_ICON_DISTANCE-5, areaX+totalWidth/2+MINI_ICON_TRIANGLE_BASE/2, areaY, areaX+totalWidth/2-MINI_ICON_SKEW, areaY-MINI_ICON_DISTANCE-5, });
        	gc.setBackground(EColor.BACKGROUND);
        	
        	// Put on the icons...
        	//
        	int xIcon = areaX+(totalWidth-totalIconsWidth)/2+MINI_ICON_MARGIN;
        	int yIcon = areaY+5+nameExtent.y;
        	for (int i=0;i<miniIcons.length;i++) {
        		EImage miniIcon = miniIcons[i];
        		Point bounds = gc.getImageBounds(miniIcon);
        		boolean enabled=false;
        		switch(i) {
        		case 0: // INPUT
        			enabled=!jobEntryCopy.isStart();
                	areaOwners.add(new AreaOwner(AreaType.JOB_ENTRY_MINI_ICON_INPUT, xIcon, yIcon, bounds.x, bounds.y, jobMeta, jobEntryCopy));
        			break;
        		case 1: // EDIT
        			enabled=true;
                	areaOwners.add(new AreaOwner(AreaType.JOB_ENTRY_MINI_ICON_EDIT, xIcon, yIcon, bounds.x, bounds.y, jobMeta, jobEntryCopy));
        			break;
        		case 2: // Job entry context menu
        			enabled=true;
        			areaOwners.add(new AreaOwner(AreaType.JOB_ENTRY_MINI_ICON_CONTEXT, xIcon, yIcon, bounds.x, bounds.y, jobMeta, jobEntryCopy));
                	break;
        		case 3: // OUTPUT
        			enabled=true;
                	areaOwners.add(new AreaOwner(AreaType.JOB_ENTRY_MINI_ICON_OUTPUT, xIcon, yIcon, bounds.x, bounds.y, jobMeta, jobEntryCopy));
        			break;
        		}
        		if (enabled) {
        			gc.setAlpha(255);
        		} else {
        			gc.setAlpha(100);
        		}
        		gc.drawImage(miniIcon, xIcon, yIcon);
        		xIcon+=bounds.x+5;
        	}        	
        }        

	}

	protected void drawJobHop(JobHopMeta hop, boolean candidate) {
		if (hop == null || hop.getFromEntry() == null || hop.getToEntry() == null)
			return;
		if (!hop.getFromEntry().isDrawn() || !hop.getToEntry().isDrawn())
			return;

		drawLine(hop, candidate);
	}

	protected void drawLine(JobHopMeta jobHop, boolean is_candidate) {
		int line[] = getLine(jobHop.getFromEntry(), jobHop.getToEntry());

		gc.setLineWidth(linewidth);
		EColor col;

		if (jobHop.getFromEntry().isLaunchingInParallel()) {
			gc.setLineStyle(ELineStyle.PARALLEL);
		} else {
			gc.setLineStyle(ELineStyle.SOLID);
		}

		if (is_candidate) {
			col = EColor.BLUE;
		} else if (jobHop.isEnabled()) {
			if (jobHop.isUnconditional()) {
				col = EColor.BLACK;
			} else {
				if (jobHop.getEvaluation()) {
					col = EColor.GREEN;
				} else {
					col = EColor.RED;
				}
			}
		} else {
			col = EColor.GRAY;
		}

		gc.setForeground(col);

		if (jobHop.isSplit())
			gc.setLineWidth(linewidth + 2);
		drawArrow(line, jobHop);
		if (jobHop.isSplit())
			gc.setLineWidth(linewidth);

		gc.setForeground(EColor.BLACK);
		gc.setBackground(EColor.BACKGROUND);
		gc.setLineStyle(ELineStyle.SOLID);
	}

	protected int[] getLine(JobEntryCopy fs, JobEntryCopy ts) {
		if (fs == null || ts == null)
			return null;

		Point from = fs.getLocation();
		Point to = ts.getLocation();

		int x1 = from.x + iconsize / 2;
		int y1 = from.y + iconsize / 2;

		int x2 = to.x + iconsize / 2;
		int y2 = to.y + iconsize / 2;

		return new int[] { x1, y1, x2, y2 };
	}
	
	private void drawArrow(int line[], JobHopMeta jobHop) {
		drawArrow(line, jobHop, jobHop.getFromEntry(), jobHop.getToEntry());
	}
	
    private void drawArrow(int line[], JobHopMeta jobHop, Object startObject, Object endObject)
    {
    	Point screen_from = real2screen(line[0], line[1], offset);
        Point screen_to = real2screen(line[2], line[3], offset);
        
        drawArrow(screen_from.x, screen_from.y, screen_to.x, screen_to.y, theta, calcArrowLength(), -1, jobHop, startObject, endObject);
    }


    private void drawArrow(int x1, int y1, int x2, int y2, double theta, int size, double factor, JobHopMeta jobHop, Object startObject, Object endObject) 
    {
 		int mx, my;
		int x3;
		int y3;
		int x4;
		int y4;
		int a, b, dist;
		double angle;

		// gc.setLineWidth(1);
		// WuLine(gc, black, x1, y1, x2, y2);

		gc.drawLine(x1, y1, x2, y2);

		// What's the distance between the 2 points?
		a = Math.abs(x2 - x1);
		b = Math.abs(y2 - y1);
		dist = (int) Math.sqrt(a * a + b * b);

        // determine factor (position of arrow to left side or right side
        // 0-->100%)
        if (factor<0)
        {
	        if (dist >= 2 * iconsize)
	             factor = 1.3;
	        else
	             factor = 1.2;
        }

		// in between 2 points
		mx = (int) (x1 + factor * (x2 - x1) / 2);
		my = (int) (y1 + factor * (y2 - y1) / 2);

		// calculate points for arrowhead
		angle = Math.atan2(y2 - y1, x2 - x1) + Math.PI;

		x3 = (int) (mx + Math.cos(angle - theta) * size);
		y3 = (int) (my + Math.sin(angle - theta) * size);

		x4 = (int) (mx + Math.cos(angle + theta) * size);
		y4 = (int) (my + Math.sin(angle + theta) * size);

		gc.switchForegroundBackgroundColors();
		gc.fillPolygon(new int[] { mx, my, x3, y3, x4, y4 });
		gc.switchForegroundBackgroundColors();
		
		// Display an icon above the hop...
		//
		factor = 0.8;

		// in between 2 points
		mx = (int) (x1 + factor * (x2 - x1) / 2) - 8;
		my = (int) (y1 + factor * (y2 - y1) / 2) - 8;

		if (jobHop!=null) {
			EImage hopsIcon;
			if (jobHop.isUnconditional()) {
				hopsIcon = EImage.UNCONDITIONAL;
			} else {
				if (jobHop.getEvaluation()) {
					hopsIcon = EImage.TRUE;
				} else {
					hopsIcon = EImage.FALSE;
				}
			}
	
			Point bounds = gc.getImageBounds(hopsIcon);
			gc.drawImage(hopsIcon, mx, my);
			if (!shadow) {
				areaOwners.add(new AreaOwner(AreaType.JOB_HOP_ICON, mx, my, bounds.x, bounds.y, subject, jobHop));
			}
			
			if (jobHop.getFromEntry().isLaunchingInParallel()) {
	
				factor = 1;
	
				// in between 2 points
				mx = (int) (x1 + factor * (x2 - x1) / 2) - 8;
				my = (int) (y1 + factor * (y2 - y1) / 2) - 8;
	
				hopsIcon = EImage.PARALLEL;
				gc.drawImage(hopsIcon, mx, my);
				if (!shadow) {
					areaOwners.add(new AreaOwner(AreaType.JOB_HOP_PARALLEL_ICON, mx, my, bounds.x, bounds.y, subject, jobHop));
				}
			}	
		}
	}

	/**
	 * @return the mouseOverEntries
	 */
	public List<JobEntryCopy> getMouseOverEntries() {
		return mouseOverEntries;
	}

	/**
	 * @param mouseOverEntries
	 *            the mouseOverEntries to set
	 */
	public void setMouseOverEntries(List<JobEntryCopy> mouseOverEntries) {
		this.mouseOverEntries = mouseOverEntries;
	}

	/**
	 * @return the entryLogMap
	 */
	public Map<JobEntryCopy, String> getEntryLogMap() {
		return entryLogMap;
	}

	/**
	 * @param entryLogMap
	 *            the entryLogMap to set
	 */
	public void setEntryLogMap(Map<JobEntryCopy, String> entryLogMap) {
		this.entryLogMap = entryLogMap;
	}

	public void setStartHopEntry(JobEntryCopy startHopEntry) {
		this.startHopEntry = startHopEntry;
	}

	public void setEndHopLocation(Point endHopLocation) {
		this.endHopLocation = endHopLocation;
	}

	public void setEndHopEntry(JobEntryCopy endHopEntry) {
		this.endHopEntry = endHopEntry;
	}

	public void setNoInputEntry(JobEntryCopy noInputEntry) {
		this.noInputEntry = noInputEntry;
	}

}