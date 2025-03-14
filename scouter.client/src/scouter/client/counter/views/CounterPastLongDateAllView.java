/*
 *  Copyright 2015 LG CNS.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); 
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *
 */
package scouter.client.counter.views;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.csstudio.swt.xygraph.dataprovider.CircularBufferDataProvider;
import org.csstudio.swt.xygraph.dataprovider.Sample;
import org.csstudio.swt.xygraph.figures.Trace;
import org.csstudio.swt.xygraph.figures.Trace.PointStyle;
import org.csstudio.swt.xygraph.figures.XYGraph;
import org.eclipse.draw2d.FigureCanvas;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import scouter.client.Images;
import scouter.client.counter.actions.OpenPastLongDateAllAction;
import scouter.client.model.AgentColorManager;
import scouter.client.model.ICounterObjectSelector;
import scouter.client.model.TextProxy;
import scouter.client.net.INetReader;
import scouter.client.net.TcpProxy;
import scouter.client.popup.DualCalendarDialog;
import scouter.client.preferences.PManager;
import scouter.client.preferences.PreferenceConstants;
import scouter.client.server.Server;
import scouter.client.server.ServerManager;
import scouter.client.util.ChartUtil;
import scouter.client.util.ColorUtil;
import scouter.client.util.ConsoleProxy;
import scouter.client.util.CounterUtil;
import scouter.client.util.ExUtil;
import scouter.client.util.ImageUtil;
import scouter.client.util.TimeUtil;
import scouter.client.util.UIUtil;
import scouter.client.views.ScouterViewPart;
import scouter.lang.pack.MapPack;
import scouter.lang.value.ListValue;
import scouter.io.DataInputX;
import scouter.net.RequestCmd;
import scouter.util.CastUtil;
import scouter.util.DateUtil;
import scouter.util.StringUtil;

public class CounterPastLongDateAllView extends ScouterViewPart implements DualCalendarDialog.ILoadDualCounterDialog, ICounterObjectSelector {
	public static final String ID = CounterPastLongDateAllView.class.getName();

	private IMemento memento;
	protected String objType;
	protected String counter;
	protected int serverId;
	protected String sDate, eDate;
	
	Label serverText, sDateText, eDateText;
	DualCalendarDialog calDialog;
	Combo periodCombo;
	Composite headerComp;
	Button applyBtn;

	IWorkbenchWindow window;
	IToolBarManager man;
	
	int buffer = 0;
	private long stime, etime;
	
	boolean actionReg = false;
	
	@Override
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		this.memento = memento;
	}

	public String getCount() {
		return counter;
	}

	public void setInput(String sDate, String eDate, String objType, String counter, int serverId) throws Exception {
		this.sDate = sDate;
		this.eDate = eDate;
		this.objType = objType;
		this.counter = counter;
		this.serverId = serverId;
		setViewTab(objType, counter, serverId, false);
		
		stime = DateUtil.getTime(sDate, "yyyyMMdd");
		etime = DateUtil.getTime(eDate, "yyyyMMdd") + DateUtil.MILLIS_PER_DAY;
		this.xyGraph.primaryXAxis.setRange(stime, etime);
		
		Server server = ServerManager.getInstance().getServer(serverId);
		String counterUnit = "";
		String counterDisplay = "";
		if(server != null){
			counterUnit = server.getCounterEngine().getCounterUnit(objType, counter);
			counterDisplay = server.getCounterEngine().getCounterDisplayName(objType, counter);
			desc = "(Period) [" + sDate.substring(0, 4) + "-" + sDate.substring(4, 6) + "-" + sDate.substring(6, 8) + 
					" ~ " + eDate.substring(0, 4) + "-" + eDate.substring(4, 6) + "-" + eDate.substring(6, 8) +
					"] All " + counterDisplay;
		}
		serverText.setText("ⓢ"+((server == null)? "?":server.getName())+" |"+(!"".equals(counterUnit)?" ("+counterUnit+")":""));
		sDateText.setText(DateUtil.format(stime, "yyyy-MM-dd"));
		eDateText.setText(DateUtil.format(etime-1, "yyyy-MM-dd"));
		
		buffer = (int) ((etime - stime) / DateUtil.MILLIS_PER_FIVE_MINUTE);
		
		Iterator<Trace> itr = traces.values().iterator();
		while (itr.hasNext()) {
			Trace tr = itr.next();
			this.xyGraph.removeTrace(tr);
		}
		traces.clear();
		
		ExUtil.asyncRun(new Runnable() {
			public void run() {
				load(CounterPastLongDateAllView.this.sDate, CounterPastLongDateAllView.this.eDate, CounterPastLongDateAllView.this.counter);
			}
		});
	}

	private void load(String sDate, String eDate, final String counter) {
		final ArrayList<MapPack> values = new ArrayList<MapPack>();
		TcpProxy tcp = TcpProxy.getTcpProxy(serverId);
		try {
			MapPack param = new MapPack();
			param.put("sDate", sDate);
			param.put("eDate", eDate);
			param.put("objType", objType);
			param.put("counter", counter);
			
			tcp.process(RequestCmd.COUNTER_PAST_LONGDATE_ALL, param, new INetReader() {
				public void process(DataInputX in) throws IOException {
					MapPack mpack = (MapPack) in.readPack();
					values.add(mpack);
				};
			});
			
		} catch (Throwable t) {
			ConsoleProxy.errorSafe(t.toString());
		} finally {
			TcpProxy.putTcpProxy(tcp);
		}
		
		ExUtil.exec(this.canvas, new Runnable() {
			public void run() {
				double max = 0;
				for (MapPack mpack : values) {
					int objHash = mpack.getInt("objHash");
					
					checkAllList(objHash);
					if(!existInSelectedList(objHash))
						continue;
					
					//String insName = mpack.getText("objName");
					ListValue time = mpack.getList("time");
					ListValue value = mpack.getList("value");

					Trace trace = intern(objHash);
					CircularBufferDataProvider provider = (CircularBufferDataProvider) trace.getDataProvider();
					
//					provider.clearTrace();
					for (int i = 0; time != null && i < time.size(); i++) {
						long x = time.getLong(i);
						double y = value.getDouble(i);
						provider.addSample(new Sample(x, y));
					}
					max = Math.max(ChartUtil.getMax(provider.iterator()), max);
				}
				if (CounterUtil.isPercentValue(objType, counter)) {
					xyGraph.primaryYAxis.setRange(0, 100);
				} else {
						xyGraph.primaryYAxis.setRange(0, max);
				}
				redraw();
				applyBtn.setEnabled(true);
			}
		});
	}

	private void checkAllList(int objHash){
		if(objHashAll.indexOf(objHash) < 0){
			objHashAll.add(objHash);
			objHashSelected.add(objHash);
		}
	}
	
	private boolean existInSelectedList(int objHash){
		if(objHashSelected.indexOf(objHash) < 0){
			return false;
		}else{
			return true;
		}
	}
	
	private ArrayList<Integer> objHashAll = new ArrayList<Integer>();
	private ArrayList<Integer> objHashSelected = new ArrayList<Integer>();
	
	private void duplicateView() {
		Server server = ServerManager.getInstance().getServer(serverId);
		String counterDisplay = "";
		if(server != null){
			counterDisplay = server.getCounterEngine().getCounterDisplayName(objType, counter);
		}
		
		Action duplicateAction = new OpenPastLongDateAllAction(window, counterDisplay, objType, counter, Images.getCounterImage(objType, counter, serverId), sDate, eDate, serverId);
		duplicateAction.run();
	}
	
	protected XYGraph xyGraph;
	protected FigureCanvas canvas;

	private int leftMargin = 0;
	private Composite marginTargetComposite;
	
	public void createPartControl(Composite parent) {
		window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		
		Composite composite = new Composite(parent, SWT.NONE);
		GridLayout gLayout = new GridLayout(1, true);
		gLayout.horizontalSpacing = 0;
		gLayout.marginHeight = 0;
		gLayout.marginWidth = 0;
		composite.setLayout(gLayout);
		createUpperMenu(composite);
		
		Composite chartComposite = new Composite(composite, SWT.NONE);
		chartComposite.setLayoutData(new GridData(GridData.FILL, GridData.FILL, true, true));
		chartComposite.setLayout(UIUtil.formLayout(0, 0));
		this.marginTargetComposite = chartComposite;
		chartComposite.setBackground(ColorUtil.getInstance().getColor(SWT.COLOR_WHITE));
		
		canvas = new FigureCanvas(chartComposite);
		canvas.setScrollBarVisibility(FigureCanvas.NEVER);
		canvas.setBackground(ColorUtil.getInstance().getColor(SWT.COLOR_WHITE));

		canvas.setLayoutData(UIUtil.formData(0, leftMargin, 0, 0, 100, 0, 100, 0));
		
		canvas.addControlListener(new ControlListener() {
			boolean lock = false;
			public void controlResized(ControlEvent e) {
				org.eclipse.swt.graphics.Rectangle r = canvas.getClientArea();
				if (!lock) {
					lock = true;
					if (ChartUtil.isShowLegendAllowSize(r.width, r.height)) {
						xyGraph.setShowLegend(true);
					} else {
						xyGraph.setShowLegend(false);
					}
					if (ChartUtil.isShowDescriptionAllowSize(r.height)) { 
						CounterPastLongDateAllView.this.setContentDescription(desc);
					} else {
						CounterPastLongDateAllView.this.setContentDescription("");
					}
					r = canvas.getClientArea();
					lock = false;
				}
				xyGraph.setSize(r.width, r.height);
			}

			public void controlMoved(ControlEvent e) {
			}
		});
		xyGraph = new XYGraph();

		canvas.setContents(xyGraph);

		xyGraph.primaryXAxis.setDateEnabled(true);
		xyGraph.primaryXAxis.setShowMajorGrid(true);
		xyGraph.primaryYAxis.setAutoScale(true);

		xyGraph.primaryYAxis.setShowMajorGrid(true);
		
		xyGraph.primaryXAxis.setTitle("");
		xyGraph.primaryYAxis.setTitle("");
		
		
		man = getViewSite().getActionBars().getToolBarManager();
		
		canvas.addKeyListener(new KeyListener() {
			public void keyReleased(KeyEvent e) {
			}
			public void keyPressed(KeyEvent e) {
				if(e.keyCode == SWT.F5){
					ExUtil.asyncRun(new Runnable() {
						public void run() {
							try {
								setInput(sDate, eDate, objType, counter, serverId);
							} catch (Exception e) {
								e.printStackTrace();
							}
						}
					});
				}
			}
		});
		
		man.add(new Action("Reload", ImageUtil.getImageDescriptor(Images.refresh)) {
			public void run() {
				ExUtil.exec(new Runnable() {
					public void run() {
						try {
							setInput(sDate, eDate, objType, counter, serverId);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
			}
		});
		
		man.add(new Separator());
		man.add(new Action("Duplicate", ImageUtil.getImageDescriptor(Images.copy)) {
			public void run() {
				ExUtil.exec(new Runnable() {
					public void run() {
						duplicateView();
					}
				});
			}
		});
		
		restoreState();
	}
	
	private void createUpperMenu(Composite composite) {
		headerComp = new Composite(composite, SWT.NONE);
		headerComp.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
		headerComp.setLayout(UIUtil.formLayout(0, 0));
		
		long initialTime = TimeUtil.getCurrentTime(serverId) - DatePeriodUnit.A_DAY.getTime();
		
		applyBtn = new Button(headerComp, SWT.PUSH);
		applyBtn.setLayoutData(UIUtil.formData(null, -1, 0, 2, 100, -5, null, -1));
		applyBtn.setText("Apply");
		applyBtn.addListener(SWT.Selection, new Listener() {
			public void handleEvent(Event event) {
				switch (event.type) {
				case SWT.Selection:
					((Button)event.widget).setEnabled(false);
					try {
						setInput(sDate, eDate, objType, counter, serverId);
					} catch (Exception e) {
						e.printStackTrace();
					}
					break;
				}
			}
		});
		
		Button manualBtn = new Button(headerComp, SWT.PUSH);
		manualBtn.setImage(Images.CTXMENU_RDC);
		manualBtn.setText("Manual");
		manualBtn.setLayoutData(UIUtil.formData(null, -1, 0, 2, applyBtn, -5, null, -1));
		manualBtn.addListener(SWT.Selection, new Listener() {
			public void handleEvent(Event event) {
				switch (event.type) {
				case SWT.Selection:
					Display display = Display.getCurrent();
					if (display == null) {
						display = Display.getDefault();
					}
					
					calDialog = new DualCalendarDialog(display, CounterPastLongDateAllView.this);
					calDialog.show(UIUtil.getMousePosition());
					
					break;
				}
			}
		});
		
		periodCombo =  new Combo(headerComp, SWT.VERTICAL | SWT.BORDER | SWT.READ_ONLY);
		periodCombo.setLayoutData(UIUtil.formData(null, -1, 0, 3, manualBtn, -5, null, -1));
        ArrayList<String> periodStrList = new ArrayList<String>();
		for (DatePeriodUnit minute : DatePeriodUnit.values()) {
			periodStrList.add(minute.getLabel());
		}
		periodCombo.setItems (periodStrList.toArray(new String[DatePeriodUnit.values().length]));
		periodCombo.select(2);
		periodCombo.addSelectionListener(new SelectionAdapter() {
			public void widgetSelected(SelectionEvent e) {
				if(((Combo)e.widget).getSelectionIndex() == 0){
					setStartEndDate(30);
				}else if(((Combo)e.widget).getSelectionIndex() == 1){
					setStartEndDate(7);
				}else{
					setStartEndDate(1);
				}
			}

			private void setStartEndDate(int i) {
				long yesterday = TimeUtil.getCurrentTime(serverId) - DatePeriodUnit.A_DAY.getTime();
				long startDate = TimeUtil.getCurrentTime(serverId) - (DatePeriodUnit.A_DAY.getTime() * i);
				sDateText.setText(DateUtil.format(startDate, "yyyy-MM-dd"));
				eDateText.setText(DateUtil.format(yesterday, "yyyy-MM-dd"));
				
				sDate = DateUtil.format(startDate, "yyyyMMdd");
				eDate = DateUtil.format(yesterday, "yyyyMMdd");
				
				stime = DateUtil.getTime(sDate, "yyyyMMdd");
				etime = DateUtil.getTime(eDate, "yyyyMMdd") + DateUtil.MILLIS_PER_DAY;
			}
			
		});
		
		eDateText = new Label(headerComp, SWT.NONE);
		eDateText.setLayoutData(UIUtil.formData(null, -1, 0, 7, periodCombo, -5, null, -1));
		eDateText.setText(DateUtil.format(initialTime-1, "yyyy-MM-dd"));
		
		Label windbarLabel = new Label(headerComp, SWT.NONE);
		windbarLabel.setLayoutData(UIUtil.formData(null, -1, 0, 7, eDateText, -5, null, -1));
        windbarLabel.setText("~");
		
        sDateText = new Label(headerComp, SWT.NONE);
		sDateText.setLayoutData(UIUtil.formData(null, -1, 0, 7, windbarLabel, -5, null, -1));
		sDateText.setText(DateUtil.format(initialTime, "yyyy-MM-dd"));
		
		serverText = new Label(headerComp, SWT.NONE | SWT.RIGHT);
		serverText.setLayoutData(UIUtil.formData(0, 0, 0, 7, sDateText, -5, null, -1));
		serverText.setText("ⓢ");
	}

	public void setFocus() {
		statusMessage = desc + " - setInput(String sDate:"+sDate+", String eDate:"+eDate+", String objType:"+objType+", String counter:"+counter+", int serverId:"+serverId+")";
		super.setFocus();
	}

	public void redraw() {
		if (canvas != null && canvas.isDisposed() == false) {
			canvas.redraw();
			xyGraph.repaint();
		}
	}

	private Map<Integer, Trace> traces = new HashMap<Integer, Trace>();

	private synchronized Trace intern(int objHash) {
		Trace trace = traces.get(objHash);
		if (trace != null)
			return trace;

		CircularBufferDataProvider traceDataProvider = new CircularBufferDataProvider(true);
		traceDataProvider.setBufferSize(buffer);
		traceDataProvider.setCurrentXDataArray(new double[] {});
		traceDataProvider.setCurrentYDataArray(new double[] {});

		
		String name = StringUtil.trimToEmpty(TextProxy.object.getLoadText(sDate, objHash, serverId));
		
		// create the trace
		trace = new Trace(name, xyGraph.primaryXAxis, xyGraph.primaryYAxis, traceDataProvider);

		// set trace property
		trace.setPointStyle(PointStyle.NONE);

		trace.getXAxis().setTitle("");
		trace.getYAxis().setTitle("");
		trace.setLineWidth(PManager.getInstance().getInt(PreferenceConstants.P_CHART_LINE_WIDTH));
		trace.getXAxis().setFormatPattern("yyyy-MM-dd\n  HH:mm:ss");
		trace.getYAxis().setFormatPattern("#,##0");
		trace.setTraceColor(AgentColorManager.getInstance().assignColor(objType, objHash));

		xyGraph.addTrace(trace);
		traces.put(objHash, trace);
		return trace;
	}
	
	public void saveState(IMemento memento) {
		super.saveState(memento);
		memento = memento.createChild(ID);
		memento.putString("counter", counter);
		memento.putString("objType", objType);
		memento.putString("sDate", this.sDate);
		memento.putString("eDate", this.eDate);
		memento.putInteger("serverId", serverId);
	}

	private void restoreState() {
		if (memento == null)
			return;
		IMemento m = memento.getChild(ID);
		String counter = m.getString("counter");
		String objType = m.getString("objType");
		String sDate = CastUtil.cString(m.getString("sDate"));
		String eDate = CastUtil.cString(m.getString("eDate"));
		int serverId = CastUtil.cint(m.getInteger("serverId"));
		try {
			setInput(sDate, eDate, objType, counter, serverId);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void onPressedOk(long startTime, long endTime) {
	}
	public void onPressedCancel() {
	}
	public void onPressedOk(String sDate, String eDate) {
		try {
			setInput(sDate, eDate, objType, counter, serverId);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
	}
	
	public enum DatePeriodUnit {
		A_MONTH ("1 Month", 30 * 24 * 60 * 60 * 1000),
		A_WEEK ("1 Week",  7 * 24 * 60 * 60 * 1000),
		A_DAY ("1 Day", 24 * 60 * 60 * 1000);
		
		private String label;
		private long time;
		
		private DatePeriodUnit(String label,  long time) {
			this.label = label;
			this.time = time;
		}
		
		public String getLabel() {
			return this.label;
		}
		
		public long getTime() {
			return this.time;
		}
		
		public static DatePeriodUnit fromString(String text) {
			if (text != null) {
				for (DatePeriodUnit b : DatePeriodUnit.values()) {
					if (text.equalsIgnoreCase(b.label)) {
						return b;
					}
				}
			}
			return null;
		}
	}
	
	public void setSelections(ArrayList<Integer> selections) {
		Iterator<Integer> itr = traces.keySet().iterator();
		while(itr.hasNext()){
			xyGraph.removeTrace(traces.get(itr.next()));
		}
		objHashSelected = new ArrayList<Integer>(selections);
		try {
			setInput(sDate, eDate, objType, counter, serverId);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
}