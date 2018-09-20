/*
Copyright 2018 Jigsaw Operations LLC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package app.intra;

import com.google.common.net.InternetDomainName;
import com.google.firebase.crash.FirebaseCrash;

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import app.intra.util.CountryMap;
import app.intra.util.DnsPacket;
import app.intra.util.DnsTransaction;

/**
 * The main screen of the app is implemented as a Recycler, allowing quasi-infinite scrolling.
 * This scrolling is used to display the DNS query history, if enabled by the user.
 *
 * Showing history creates a resource utilization challenge.  Keeping an unbounded amount of history
 * would result in unbounded memory usage, a major problem for a long-lived service.  However,
 * erasing history at some limit would likely cause transactions to disappear from the screen while
 * the user is looking at them, a surprising and frustrating experience.
 *
 * This design balances these requirements by recording unbounded history during the lifetime of
 * the activity, and discarding it when the activity is destroyed.  This ensures that data does not
 * disappear while the user is looking at it, and allows Android to recover the memory when it's
 * needed.
 *
 * When the activity is recreated, it retrieves bounded history (the last 100 queries) from the
 * DnsVpnService.
 */
public class RecyclerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

  // The recycler contains two types of elements: the controls (just once, at the top)
  // and a potentially large number of transaction rows.
  private static final int TYPE_CONTROLS = 0;
  private static final int TYPE_TRANSACTION = 1;

  // Hold a reference to the main activity class, which provides the control view.
  private MainActivity activity;
  private CountryMap countryMap = null;

  // ARGB colors to use as the background for condensed and expanded transaction rows.
  private final int condensedColor, expandedColor;

  public RecyclerAdapter(MainActivity activity) {
    super();
    this.activity = activity;

    // Populate colors for use by TransactionViewHolder.
    condensedColor = activity.getResources().getColor(R.color.light);
    expandedColor = activity.getResources().getColor(R.color.floating);
  }

  private void activateCountryMap() {
    if (countryMap != null) {
      return;
    }
    try {
      countryMap = new CountryMap(activity.getAssets());
    } catch (IOException e) {
      FirebaseCrash.report(e);
    }
  }

  // Exposes the control view to the Recycler.  This class is trivial because MainActivity is
  // responsible for maintaining the control view.
  public static class ControlViewHolder extends RecyclerView.ViewHolder {

    public View controlView;

    public ControlViewHolder(View v) {
      super(v);
      controlView = v;
    }
  }

  // Exposes a transaction row view to the Recycler.  This class is responsible for updating
  // the contents of the view to reflect the intended item.
  public final class TransactionViewHolder extends RecyclerView.ViewHolder implements View
      .OnClickListener {

    private Transaction transaction;

    // Overall view
    private final View rowView;

    // Contents of the condensed view
    private final TextView hostnameView;
    private final TextView timeView;
    private final TextView flagView;
    private final ToggleButton expandButton;

    // Contents of the expanded details view
    private final View detailsView;
    private final TextView fqdnView;
    private final TextView typeView;
    private final TextView latencyView;
    private final TextView resolverView;
    private final TextView responseView;

    TransactionViewHolder(View v) {
      super(v);

      rowView = v;

      hostnameView = v.findViewById(R.id.hostname);
      timeView = v.findViewById(R.id.response_time);
      flagView = v.findViewById(R.id.flag);
      expandButton = v.findViewById(R.id.expand);

      expandButton.setOnClickListener(this);

      detailsView = v.findViewById(R.id.details);
      fqdnView = v.findViewById(R.id.fqdn);
      typeView = v.findViewById(R.id.qtype);
      latencyView = v.findViewById(R.id.latency_small);
      resolverView = v.findViewById(R.id.resolver);
      responseView = v.findViewById(R.id.response);

    }

    private void setExpanded(boolean expanded) {
      detailsView.setVisibility(expanded ? View.VISIBLE : View.GONE);
      rowView.setBackgroundColor(expanded ? expandedColor : condensedColor);
      expandButton.setChecked(expanded);

      if (expanded) {
        // Make sure the details are up to date.
        fqdnView.setText(transaction.fqdn);
        typeView.setText(transaction.typename);
        latencyView.setText(transaction.latency);
        resolverView.setText(transaction.resolver);
        responseView.setText(transaction.response);
      }
    }

    public void update(Transaction transaction) {
      // This function can be run up to a dozen times while blocking rendering, so it needs to be
      // as brief as possible.
      this.transaction = transaction;
      hostnameView.setText(transaction.hostname);
      timeView.setText(transaction.time);
      flagView.setText(transaction.flag);

      setExpanded(transaction.expanded);
    }

    @Override
    public void onClick(View view) {
      int position = this.getAdapterPosition();
      Transaction transaction = getItem(position);
      transaction.expanded = !transaction.expanded;
      notifyItemChanged(position);
    }
  }

  // Class representing a view of a DnsTransaction.  Computing the value of all these strings can
  // take over 10 ms, so this class ensures they're only computed once per transaction, instead of
  // being recomputed every time a transaction row becomes visible during scrolling.
  private final class Transaction {
    // If true, the panel is expanded to show details.
    boolean expanded = false;

    // Human-readable representation of this transaction.
    final String fqdn;      // Fully qualified domain name of the query
    final String hostname;  // Truncated hostname for short display
    final String time;      // The time of the response, e.g. 10:32:15
    final String latency;   // The latency of the response, e.g. "150 ms"
    final String typename;  // Typically "A" or "AAAA"
    final String resolver;  // The resolver IP address and country code
    final String response;  // The first response IP in the RRset, for an A or AAAA response.
    final String flag;      // The flag of the response IP, as an emoji.

    Transaction(@NonNull DnsTransaction transaction) {
      fqdn = transaction.name;
      hostname = getETldPlus1(transaction.name);

      int hour = transaction.responseCalendar.get(Calendar.HOUR_OF_DAY);
      int minute = transaction.responseCalendar.get(Calendar.MINUTE);
      int second = transaction.responseCalendar.get(Calendar.SECOND);
      time = String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);

      String template = activity.getResources().getString(R.string.latency_ms);
      latency = String.format(template, transaction.responseTime - transaction.queryTime);

      typename = getTypeName(transaction.type);

      InetAddress serverAddress;
      try {
        serverAddress = InetAddress.getByName(transaction.serverIp);
      } catch (UnknownHostException e) {
        serverAddress = null;
      }

      if (serverAddress != null) {
        @Nullable String countryCode = getCountryCode(serverAddress);
        resolver = makeAddressPair(countryCode, serverAddress.getHostAddress());
      } else {
        resolver = transaction.serverIp;
      }

      if (transaction.status == DnsTransaction.Status.COMPLETE) {
        DnsPacket packet = null;
        String err = null;
        try {
          packet = new DnsPacket(transaction.response);
        } catch (ProtocolException e) {
          err = e.getMessage();
        }
        if (packet != null) {
          List<InetAddress> addresses = packet.getResponseAddresses();
          if (addresses.size() > 0) {
            InetAddress destination = addresses.get(0);
            @Nullable String countryCode = getCountryCode(destination);
            response = makeAddressPair(countryCode, destination.getHostAddress());
            flag = getFlag(countryCode);
          } else {
            response = "NXDOMAIN";
            flag = "\u2754";  // White question mark
          }
        } else {
          response = err;
          flag = "\u26a0";  // Warning sign
        }
      } else {
        response = transaction.status.name();
        if (transaction.status == DnsTransaction.Status.CANCELED) {
          flag = "\u274c";  // "X" mark
        } else {
          flag = "\u26a0";  // Warning sign
        }
      }
    }

    private String getTypeName(int type) {
      // From https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4
      final String[] names = {
          "0",
          "A",
          "NS",
          "MD",
          "MF",
          "CNAME",
          "SOA",
          "MB",
          "MG",
          "MR",
          "NULL",
          "WKS",
          "PTR",
          "HINFO",
          "MINFO",
          "MX",
          "TXT",
          "RP",
          "AFSDB",
          "X25",
          "ISDN",
          "RT",
          "NSAP",
          "NSAP+PTR",
          "SIG",
          "KEY",
          "PX",
          "GPOS",
          "AAAA",
          "LOC",
          "NXT",
          "EID",
          "NIMLOC",
          "SRV",
          "ATMA",
          "NAPTR",
          "KX",
          "CERT",
          "A6",
          "DNAME",
          "SINK",
          "OPT",
          "APL",
          "DS",
          "SSHFP",
          "IPSECKEY",
          "RRSIG",
          "NSEC",
          "DNSKEY",
          "DHCID",
          "NSEC3",
          "NSEC3PARAM",
          "TLSA",
          "SMIMEA"
      };
      if (type < names.length) {
        return names[type];
      }
      return String.format(Locale.ROOT, "%d", type);
    }

    // Converts a two-character ISO country code into a flag emoji.
    private String getFlag(@Nullable String countryCode) {
      if (countryCode == null) {
        return "";
      }
      // Flag emoji consist of two "regional indicator symbol letters", which are
      // Unicode characters that correspond to the English alphabet and are arranged in the same
      // order.  Therefore, to convert from a country code to a flag, we simply need to apply an
      // offset to each character, shifting it from the normal A-Z range into the region indicator
      // symbol letter range.
      int alphaBase = 'A';  // Start of alphabetic country code characters.
      int flagBase = 0x1F1E6;  // Start of regional indicator symbol letters.
      int offset = flagBase - alphaBase;
      int firstHalf = Character.codePointAt(countryCode, 0) + offset;
      int secondHalf = Character.codePointAt(countryCode, 1) + offset;
      return new String(Character.toChars(firstHalf)) + new String(Character.toChars(secondHalf));
    }

    // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
    private String getETldPlus1(String fqdn) {
      try {
        InternetDomainName name = InternetDomainName.from(fqdn);
        try {
          return name.topPrivateDomain().toString();
        } catch (IllegalStateException e){
          // The name doesn't end in a recognized TLD.  This can happen for randomly generated
          // names, or when new TLDs are introduced.
          List<String> parts = name.parts();
          int size = parts.size();
          if (size >= 2) {
            return parts.get(size - 2) + "." + parts.get(size - 1);
          } else if (size == 1) {
            return parts.get(0);
          } else {
            // Empty input?
            return fqdn;
          }
        }
      } catch (IllegalArgumentException e) {
        // If fqdn is not a valid domain name, InternetDomainName.from() will throw an
        // exception.  Since this function is only for aesthetic purposes, we can
        // return the input unmodified in this case.
        return fqdn;
      }
    }

    // Return a two-letter ISO country code, or null if that fails.
    private @Nullable String getCountryCode(InetAddress address) {
      activateCountryMap();
      if (countryMap == null) {
        return null;
      }
      return countryMap.getCountryCode(address);
    }

    private String makeAddressPair(@Nullable String countryCode, String ipAddress) {
      if (countryCode == null) {
        return ipAddress;
      }
      return String.format("%s (%s)", countryCode, ipAddress);
    }
  }

  // Store of transactions, used for appending and lookup by index.
  private List<Transaction> transactions = new ArrayList<>();

  /**
   * Replace the current list of transactions with these.
   * A null argument is treated as an empty list.
   */
  public void reset(Queue<DnsTransaction> transactions) {
    this.transactions.clear();
    if (transactions != null) {
      for (DnsTransaction t : transactions) {
        this.transactions.add(new Transaction(t));
      }
    } else {
      countryMap = null;
    }
    this.notifyDataSetChanged();
  }

  /**
   * Add a new transaction to the top of the displayed list
   */
  public void add(DnsTransaction transaction) {
    transactions.add(new Transaction(transaction));
    this.notifyItemInserted(1);
  }

  private Transaction getItem(int position) {
    return transactions.get(transactions.size() - position);
  }

  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    if (viewType == TYPE_CONTROLS) {
      View v = activity.getControlView(parent);
      return new ControlViewHolder(v);
    } else {
      assert viewType == TYPE_TRANSACTION;
      View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.transaction_row,
          parent, false);

      // Workaround for lack of vector drawable background support in pre-Lollipop Android.
      View expand = v.findViewById(R.id.expand);
      // getDrawable automatically rasterizes vector drawables as needed on pre-Lollipop Android.
      // See https://stackoverflow.com/questions/29041027/android-getresources-getdrawable-deprecated-api-22
      Drawable expander = ContextCompat.getDrawable(activity, R.drawable.expander);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        // Only available in API 16+.
        expand.setBackground(expander);
      } else {
        // Deprecated starting in API 16.
        expand.setBackgroundDrawable(expander);
      }

      return new TransactionViewHolder(v);
    }
  }

  @Override
  public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
    if (holder instanceof ControlViewHolder) {
      assert position == 0;
    } else {
      assert holder instanceof TransactionViewHolder;
      Transaction transaction = getItem(position);
      ((TransactionViewHolder) holder).update(transaction);
    }
  }

  @Override
  public int getItemCount() {
    return transactions.size() + 1;
  }

  @Override
  public int getItemViewType(int position) {
    return position == 0 ? TYPE_CONTROLS : TYPE_TRANSACTION;
  }
}
