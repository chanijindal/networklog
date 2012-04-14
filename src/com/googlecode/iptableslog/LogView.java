package com.googlecode.iptableslog;

import android.util.Log;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Filter;
import android.widget.Filterable;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.graphics.drawable.Drawable;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Toast;
import android.util.TypedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;

public class LogView extends Activity
{
  // bound to adapter
  protected ArrayList<ListItem> listData;
  // buffers incoming log entries
  protected ArrayList<ListItem> listDataBuffer;
  // holds all entries, used for filtering
  protected ArrayList<ListItem> listDataUnfiltered;
  protected long maxLogEntries;
  private CustomAdapter adapter;
  private ListViewUpdater updater;
  public TextView statusText;

  protected class ListItem {
    protected Drawable mIcon;
    protected int mUid;
    protected String mUidString;
    protected String in;
    protected String out;
    protected String mName;
    protected String mNameLowerCase;
    protected String srcAddr;
    protected int srcPort;
    protected String dstAddr;
    protected int dstPort;
    protected int len;
    protected long timestamp;

    ListItem(Drawable icon, int uid, String name) {
      mIcon = icon;
      mUid = uid;
      mUidString = String.valueOf(uid);
      mName = name;
      mNameLowerCase = name.toLowerCase();
    }

    @Override
      public String toString() {
        return mName;
      }
  }

  public void clear() {
    synchronized(listData) {
      synchronized(listDataBuffer) {
        synchronized(listDataUnfiltered) {
          listData.clear();
          listDataBuffer.clear();
          listDataUnfiltered.clear();
        }
      }
    }
    adapter.notifyDataSetChanged();
  }

  public void refreshAdapter() {
    adapter.notifyDataSetChanged();
  }

  /** Called when the activity is first created. */
  @Override
    public void onCreate(Bundle savedInstanceState)
    {
      super.onCreate(savedInstanceState);

      MyLog.d("LogView created");

      LinearLayout layout = new LinearLayout(this);
      layout.setOrientation(LinearLayout.VERTICAL);

      statusText = new TextView(this);
      layout.addView(statusText);

      if(IptablesLog.data == null) {
        listData = new ArrayList<ListItem>();
        listDataBuffer = new ArrayList<ListItem>();
        listDataUnfiltered = new ArrayList<ListItem>();
      } else {
        restoreData(IptablesLog.data);
      }

      adapter = new CustomAdapter(this, R.layout.logitem, listData);

      ListView listView = new ListView(this);
      listView.setAdapter(adapter);
      listView.setTextFilterEnabled(true);
      listView.setFastScrollEnabled(true);
      listView.setSmoothScrollbarEnabled(false);
      listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
      listView.setStackFromBottom(true);

      listView.setOnItemClickListener(new CustomOnItemClickListener());

      layout.addView(listView);

      setContentView(layout);

      maxLogEntries = IptablesLog.settings.getMaxLogEntries();

      if(IptablesLog.filterTextInclude.length() > 0 || IptablesLog.filterTextExclude.length() > 0) {
        // trigger filtering
        setFilter("");
        adapter.notifyDataSetChanged();
      }
    }

  private class CustomOnItemClickListener implements OnItemClickListener {
    @Override
      public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        ListItem item = listData.get(position);
        startActivity(new Intent(getApplicationContext(), AppTimelineGraph.class)
            .putExtra("app_uid", item.mUidString)
            .putExtra("src_addr", item.srcAddr)
            .putExtra("src_port", item.srcPort)
            .putExtra("dst_addr", item.dstAddr)
            .putExtra("dst_port", item.dstPort));
      }
  }

  public void startUpdater() {
    updater = new ListViewUpdater();
    new Thread(updater, "LogViewUpdater").start();
  }

  public void restoreData(IptablesLogData data) {
    listData = data.logViewListData;
    listDataBuffer = data.logViewListDataBuffer;
    listDataUnfiltered = data.logViewListDataUnfiltered;
  }

  @Override
    public void onBackPressed() {
      IptablesLog parent = (IptablesLog) getParent();
      parent.confirmExit(this);
    }

  public void onNewLogEntry(final LogEntry entry) {
    ApplicationsTracker.AppEntry appEntry = ApplicationsTracker.installedAppsHash.get(String.valueOf(entry.uid));

    if(appEntry == null) {
      MyLog.d("LogView: No appEntry for uid " + entry.uid);
      return;
    }

    final ListItem item = new ListItem(appEntry.icon, appEntry.uid, appEntry.name);

    if(entry.in != null) {
      item.in = new String(entry.in);
    } else {
      item.in = null;
    }

    if(entry.out != null) {
      item.out = new String(entry.out);
    } else {
      item.out = null;
    }

    item.srcAddr = new String(entry.src);
    item.srcPort = entry.spt;

    item.dstAddr = new String(entry.dst);
    item.dstPort = entry.dpt;

    item.len = entry.len;
    item.timestamp = entry.timestamp;

    MyLog.d("LogView: Add item: in=" + item.in + " out=" + item.out + " " + item.srcAddr + " " + item.srcPort + " " + item.dstAddr + " " + item.dstPort + " " + item.len);

    synchronized(listDataBuffer) {
      listDataBuffer.add(item);

      while(listDataBuffer.size() > maxLogEntries) {
        listDataBuffer.remove(0);
      }
    }
  }

  public void pruneLogEntries() {
    synchronized(listDataBuffer) {
      while(listDataBuffer.size() > maxLogEntries) {
        listDataBuffer.remove(0);
      }
    }

    synchronized(listDataUnfiltered) {
      while(listDataUnfiltered.size() > maxLogEntries) {
        listDataUnfiltered.remove(0);
      }
    }

    synchronized(listData) {
      while(listData.size() > maxLogEntries) {
        listData.remove(0);
      }
    }

    if(!IptablesLog.outputPaused) {
      adapter.notifyDataSetChanged();
    }
  }

  public void stopUpdater() {
    if(updater != null) {
      updater.stop();
    }
  }

  // todo: this is largely duplicated in AppView -- move to its own file
  private class ListViewUpdater implements Runnable {
    boolean running = false;
    Runnable runner = new Runnable() {
      public void run() {
        MyLog.d("LogViewUpdater enter");
        int i = 0;

        synchronized(listDataBuffer) {
          synchronized(listData) {
            synchronized(listDataUnfiltered) {
              for(ListItem item : listDataBuffer) {
                listData.add(item);
                listDataUnfiltered.add(item);
                i++;
              }

              listDataBuffer.clear();
            }
          }
        }

        synchronized(listDataUnfiltered) {
          while(listDataUnfiltered.size() > maxLogEntries) {
            listDataUnfiltered.remove(0);
          }
        }

        synchronized(listData) {
          while(listData.size() > maxLogEntries) {
            listData.remove(0);
          }
        }

        if(IptablesLog.filterTextInclude.length() > 0 || IptablesLog.filterTextExclude.length() > 0)
          // trigger filtering
        {
          setFilter("");
        }

        if(!IptablesLog.outputPaused) {
          adapter.notifyDataSetChanged();
        }

        MyLog.d("LogViewUpdater exit: added " + i + " items");
      }
    };

    public void stop() {
      running = false;
    }

    public void run() {
      running = true;
      MyLog.d("Starting LogView updater " + this);

      while(running) {
        if(listDataBuffer.size() > 0) {
          runOnUiThread(runner);
        }

        try {
          Thread.sleep(2500);
        }
        catch(Exception e) {
          Log.d("IptablesLog", "LogViewListUpdater", e);
        }
      }

      MyLog.d("Stopped LogView updater " + this);
    }
  }

  public void setFilter(CharSequence s) {
    // MyLog.d("[LogView] setFilter(" + s + ")");
    adapter.getFilter().filter(s);
  }

  private class CustomAdapter extends ArrayAdapter<ListItem> implements Filterable {
    LayoutInflater mInflater = (LayoutInflater) getSystemService(Activity.LAYOUT_INFLATER_SERVICE);
    CustomFilter filter;
    ArrayList<ListItem> originalItems = new ArrayList<ListItem>();

    public CustomAdapter(Context context, int resource, List<ListItem> objects) {
      super(context, resource, objects);
    }

    private class CustomFilter extends Filter {
      @Override
        protected FilterResults performFiltering(CharSequence constraint) {
          FilterResults results = new FilterResults();

          synchronized(listDataUnfiltered) {
            originalItems.clear();
            originalItems.addAll(listDataUnfiltered);
          }

          if(IptablesLog.filterTextInclude.length() == 0 && IptablesLog.filterTextExclude.length() == 0) {
            // MyLog.d("[LogView] no constraint item count: " + originalItems.size());
            results.values = originalItems;
            results.count = originalItems.size();
          } else {
            ArrayList<ListItem> filteredItems = new ArrayList<ListItem>();
            ArrayList<ListItem> localItems = new ArrayList<ListItem>();
            localItems.addAll(originalItems);
            int count = localItems.size();

            MyLog.d("[LogView] item count: " + count);

            if(IptablesLog.filterTextIncludeList.size() == 0) {
              filteredItems.addAll(localItems);
            } else {
              for(int i = 0; i < count; i++) {
                ListItem item = localItems.get(i);
                // MyLog.d("[LogView] testing filtered item " + item + "; includes: [" + IptablesLog.filterTextInclude + "]");

                boolean matched = false;

                String srcAddrResolved;
                String srcPortResolved;
                String dstAddrResolved;
                String dstPortResolved;

                if(IptablesLog.resolveHosts) {
                  srcAddrResolved = IptablesLog.resolver.resolveAddress(item.srcAddr);

                  if(srcAddrResolved == null) {
                    srcAddrResolved = "";
                  }

                  dstAddrResolved = IptablesLog.resolver.resolveAddress(item.dstAddr);

                  if(dstAddrResolved == null) {
                    dstAddrResolved = "";
                  }
                } else {
                  srcAddrResolved = "";
                  dstAddrResolved = "";
                }

                if(IptablesLog.resolvePorts) {
                  srcPortResolved = IptablesLog.resolver.resolveService(String.valueOf(item.srcPort));
                  dstPortResolved = IptablesLog.resolver.resolveService(String.valueOf(item.dstPort));
                } else {
                  srcPortResolved = "";
                  dstPortResolved = "";
                }

                for(String c : IptablesLog.filterTextIncludeList) {
                  if((IptablesLog.filterNameInclude && item.mNameLowerCase.contains(c))
                      || (IptablesLog.filterUidInclude && item.mUidString.equals(c))
                      || (IptablesLog.filterAddressInclude && 
                        ((item.srcAddr.contains(c) || srcAddrResolved.toLowerCase().contains(c)) 
                         || (item.dstAddr.contains(c) || dstAddrResolved.toLowerCase().contains(c))))
                      || (IptablesLog.filterPortInclude && 
                        ((String.valueOf(item.srcPort).toLowerCase().equals(c) || srcPortResolved.toLowerCase().equals(c))
                         || (String.valueOf(item.dstPort).toLowerCase().equals(c) || dstPortResolved.toLowerCase().equals(c)))))
                  {
                    matched = true;
                  }
                }

                if(matched) {
                  // MyLog.d("[LogView] adding filtered item " + item);
                  filteredItems.add(item);
                }
              }
            }

            if(IptablesLog.filterTextExcludeList.size() > 0) {
              count = filteredItems.size();

              for(int i = count - 1; i >= 0; i--) {
                ListItem item = filteredItems.get(i);
                // MyLog.d("[LogView] testing filtered item " + item + "; excludes: [" + IptablesLog.filterTextExclude + "]");

                boolean matched = false;

                String srcAddrResolved;
                String srcPortResolved;
                String dstAddrResolved;
                String dstPortResolved;

                if(IptablesLog.resolveHosts) {
                  srcAddrResolved = IptablesLog.resolver.resolveAddress(item.srcAddr);

                  if(srcAddrResolved == null) {
                    srcAddrResolved = "";
                  }

                  dstAddrResolved = IptablesLog.resolver.resolveAddress(item.dstAddr);

                  if(dstAddrResolved == null) {
                    dstAddrResolved = "";
                  }
                } else {
                  srcAddrResolved = "";
                  dstAddrResolved = "";
                }

                if(IptablesLog.resolvePorts) {
                  srcPortResolved = IptablesLog.resolver.resolveService(String.valueOf(item.srcPort));
                  dstPortResolved = IptablesLog.resolver.resolveService(String.valueOf(item.dstPort));
                } else {
                  srcPortResolved = "";
                  dstPortResolved = "";
                }

                for(String c : IptablesLog.filterTextExcludeList) {
                  if((IptablesLog.filterNameExclude && item.mNameLowerCase.contains(c))
                      || (IptablesLog.filterUidExclude && item.mUidString.contains(c))
                      || (IptablesLog.filterAddressExclude && ((item.srcAddr.contains(c) || srcAddrResolved.toLowerCase().contains(c)) || (item.dstAddr.contains(c) || dstAddrResolved.toLowerCase().contains(c))))
                      || (IptablesLog.filterPortExclude && ((String.valueOf(item.srcPort).equals(c) || srcPortResolved.toLowerCase().equals(c)) || (String.valueOf(item.dstPort).equals(c) || dstPortResolved.toLowerCase().equals(c)))))
                  {
                    matched = true;
                  }
                }

                if(matched) {
                  // MyLog.d("[LogView] removing filtered item " + item);
                  filteredItems.remove(i);
                }
              }
            }

            results.values = filteredItems;
            results.count = filteredItems.size();
          }

          return results;
        }

      @SuppressWarnings("unchecked")
        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
          final ArrayList<ListItem> localItems = (ArrayList<ListItem>) results.values;

          synchronized(listData) {
            clear();

            int count = localItems.size();

            for(int i = 0; i < count; i++) {
              add(localItems.get(i));
            }

            if(!IptablesLog.outputPaused) {
              notifyDataSetChanged();
            }
          }
        }
    }

    @Override
      public CustomFilter getFilter() {
        if(filter == null) {
          filter = new CustomFilter();
        }

        return filter;
      }

    @Override
      public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder = null;

        ImageView icon;
        TextView name;
        TextView iface;
        TextView srcAddr;
        TextView srcPort;
        TextView dstAddr;
        TextView dstPort;
        TextView len;
        TextView timestamp;

        ListItem item = getItem(position);

        if(convertView == null) {
          convertView = mInflater.inflate(R.layout.logitem, null);
          holder = new ViewHolder(convertView);
          convertView.setTag(holder);
        }

        holder = (ViewHolder) convertView.getTag();
        icon = holder.getIcon();

        if(item.mIcon == null) {
          item.mIcon = ApplicationsTracker.loadIcon(getApplicationContext(), ApplicationsTracker.installedAppsHash.get(item.mUidString).packageName);
        }

        icon.setImageDrawable(item.mIcon);

        name = holder.getName();
        name.setText("(" + item.mUid + ")" + " " + item.mName);

        iface = holder.getInterface();

        if(item.in != null && item.in.length() != 0) {
          iface.setText(item.in);
        } else {
          iface.setText(item.out);
        }

        srcAddr = holder.getSrcAddr();

        if(IptablesLog.resolveHosts) {
          String resolved = IptablesLog.resolver.resolveAddress(item.srcAddr);

          if(resolved != null) {
            srcAddr.setText("SRC: " + resolved);
          } else {
            srcAddr.setText("SRC: " + item.srcAddr);
          }
        } else {
          srcAddr.setText("SRC: " + item.srcAddr);
        }

        srcPort = holder.getSrcPort();

        if(IptablesLog.resolvePorts) {
          srcPort.setText(IptablesLog.resolver.resolveService(String.valueOf(item.srcPort)));
        } else {
          srcPort.setText(String.valueOf(item.srcPort));
        }

        dstAddr = holder.getDstAddr();

        if(IptablesLog.resolveHosts) {
          String resolved = IptablesLog.resolver.resolveAddress(item.dstAddr);

          if(resolved != null) {
            dstAddr.setText("DST: " + resolved);
          } else {
            dstAddr.setText("DST: " + item.dstAddr);
          }
        } else {
          dstAddr.setText("DST: " + item.dstAddr);
        }
        
        dstPort = holder.getDstPort();

        if(IptablesLog.resolvePorts) {
          dstPort.setText(IptablesLog.resolver.resolveService(String.valueOf(item.dstPort)));
        } else {
          dstPort.setText(String.valueOf(item.dstPort));
        }

        len = holder.getLen();
        len.setText("LEN: " + item.len);

        timestamp = holder.getTimestamp();

        timestamp.setText(Timestamp.getTimestamp(item.timestamp));

        return convertView;
      }
  }

  private class ViewHolder {
    private View mView;
    private ImageView mIcon = null;
    private TextView mName = null;
    private TextView mInterface = null;
    private TextView mSrcAddr = null;
    private TextView mSrcPort = null;
    private TextView mDstAddr = null;
    private TextView mDstPort = null;
    private TextView mLen = null;
    private TextView mTimestamp = null;

    public ViewHolder(View view) {
      mView = view;
    }

    public ImageView getIcon() {
      if(mIcon == null) {
        mIcon = (ImageView) mView.findViewById(R.id.logIcon);
      }

      return mIcon;
    }

    public TextView getName() {
      if(mName == null) {
        mName = (TextView) mView.findViewById(R.id.logName);
      }

      return mName;
    }

    public TextView getInterface() {
      if(mInterface == null) {
        mInterface = (TextView) mView.findViewById(R.id.logInterface);
      }

      return mInterface;
    }

    public TextView getSrcAddr() {
      if(mSrcAddr == null) {
        mSrcAddr = (TextView) mView.findViewById(R.id.srcAddr);
      }

      return mSrcAddr;
    }

    public TextView getSrcPort() {
      if(mSrcPort == null) {
        mSrcPort = (TextView) mView.findViewById(R.id.srcPort);
      }

      return mSrcPort;
    }

    public TextView getDstAddr() {
      if(mDstAddr == null) {
        mDstAddr = (TextView) mView.findViewById(R.id.dstAddr);
      }

      return mDstAddr;
    }

    public TextView getDstPort() {
      if(mDstPort == null) {
        mDstPort = (TextView) mView.findViewById(R.id.dstPort);
      }

      return mDstPort;
    }

    public TextView getLen() {
      if(mLen == null) {
        mLen = (TextView) mView.findViewById(R.id.len);
      }

      return mLen;
    }

    public TextView getTimestamp() {
      if(mTimestamp == null) {
        mTimestamp = (TextView) mView.findViewById(R.id.timestamp);
      }

      return mTimestamp;
    }
  }
}
