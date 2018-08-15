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

  public RecyclerAdapter(MainActivity activity) {
    super();
    this.activity = activity;
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
  public class TransactionViewHolder extends RecyclerView.ViewHolder implements View
      .OnClickListener {

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
    private String getFlag(String countryCode) {
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

    public View transactionView;

    public TransactionViewHolder(View v) {
      super(v);
      transactionView = v;
      ToggleButton expand = (ToggleButton) transactionView.findViewById(R.id.expand);
      expand.setOnClickListener(this);
    }

    private void setExpanded(boolean expanded) {
      View details = transactionView.findViewById(R.id.details);
      details.setVisibility(expanded ? View.VISIBLE : View.GONE);
      ToggleButton expand = (ToggleButton) transactionView.findViewById(R.id.expand);
      expand.setChecked(expanded);
    }

    // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
    private String getETldPlus1(String fqdn) {
      return InternetDomainName.from(fqdn).topPrivateDomain().toString();
    }

    // Return a two-letter ISO country code, or null if that fails.
    private String getCountryCode(InetAddress address) {
      activateCountryMap();
      if (countryMap == null) {
        return null;
      }
      try {
        return countryMap.getCountryCode(address);
      } catch (IOException e) {
        return null;
      }
    }

    private String makeAddressPair(String countryCode, String ipAddress) {
      return String.format("%s (%s)", countryCode, ipAddress);
    }

    public void update(Transaction transaction) {
      DnsTransaction dnsTransaction = transaction.transaction;
      TextView hostnameView = transactionView.findViewById(R.id.hostname);
      hostnameView.setText(getETldPlus1(dnsTransaction.name));
      TextView fqdnView = transactionView.findViewById(R.id.fqdn);
      fqdnView.setText(dnsTransaction.name);

      TextView timeView = transactionView.findViewById(R.id.response_time);
      int hour = dnsTransaction.responseCalendar.get(Calendar.HOUR_OF_DAY);
      int minute = dnsTransaction.responseCalendar.get(Calendar.MINUTE);
      int second = dnsTransaction.responseCalendar.get(Calendar.SECOND);
      timeView.setText(String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second));

      TextView latencyView = transactionView.findViewById(R.id.latency_small);
      String template = activity.getResources().getString(R.string.latency_ms);
      latencyView.setText(String.format(template,
          dnsTransaction.responseTime - dnsTransaction.queryTime));

      TextView typeView = transactionView.findViewById(R.id.qtype);
      typeView.setText(getTypeName(dnsTransaction.type));

      TextView resolverView = transactionView.findViewById(R.id.resolver);
      resolverView.setText("");
      try {
        InetAddress serverAddress = InetAddress.getByName(dnsTransaction.serverIp);
        String countryCode = getCountryCode(serverAddress);
        if (countryCode != null) {
          resolverView.setText(makeAddressPair(countryCode, serverAddress.getHostAddress()));
        } else {
          resolverView.setText(dnsTransaction.serverIp);
        }
      } catch (UnknownHostException e) {
        resolverView.setText(dnsTransaction.serverIp);
      }

      TextView responseView = transactionView.findViewById(R.id.response);
      TextView flagView = transactionView.findViewById(R.id.flag);
      flagView.setText("");
      if (dnsTransaction.status != DnsTransaction.Status.COMPLETE) {
        responseView.setText(dnsTransaction.status.name());
      } else {
        try {
          DnsPacket packet = new DnsPacket(dnsTransaction.response);
          List<InetAddress> addresses = packet.getResponseAddresses();
          if (addresses.size() > 0) {
            InetAddress destination = addresses.get(0);
            String countryCode = getCountryCode(destination);
            if (countryCode != null) {
              responseView.setText(makeAddressPair(countryCode, destination.getHostAddress()));
              flagView.setText(getFlag(countryCode));
            }
          } else {
            responseView.setText("NXDOMAIN");
          }
        } catch (ProtocolException e) {
          responseView.setText(e.getMessage());
        }
      }

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

  private class Transaction {

    final DnsTransaction transaction;
    boolean expanded = false;

    public Transaction(DnsTransaction transaction) {
      this.transaction = transaction;
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
