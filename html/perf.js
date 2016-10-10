function render_graphs(results, parentDomElement) {
  if(typeof parentDomElement === "undefined") {
    $('.chart, .small-chart').map(function() {
        plot($(this), results);
    });
    $('.summary').map(function() {
        summarise($(this), results);
    });
  }
  else {
    parentDomElement.find('.chart, .small-chart').map(function() {
        plot($(this), results);
    });
    parentDomElement.find('.summary').map(function() {
        summarise($(this), results);
    });
  }
}

function summarise(div, results) {
    var scenario = div.attr('data-scenario');
    var mode     = div.attr('data-mode');
    var data     = results[scenario];

    var rate;
    if (mode == 'send') {
        rate = Math.round(data['send-msg-rate']);
    }
    else if (mode == 'recv') {
        rate = Math.round(data['recv-msg-rate']);
    }
    else {
        rate = Math.round((data['send-msg-rate'] + data['recv-msg-rate']) / 2);
    }

    div.append('<strong>' + rate + '</strong>msg/s');
}

function plot(div, results) {
    var file = div.attr('data-file');

    if (file == undefined) {
        plot0(div, results);
    }
    else {
        $.ajax({
            url: file,
            success: function(data) {
                plot0(div, JSON.parse(data));
            },
            fail: function() { alert('error loading ' + file); }
        });
    }
}

function plot0(div, results) {
    var type     = div.attr('data-type');
    var scenario = div.attr('data-scenario');

    if (type == 'time') {
        var data = results[scenario];
        plot_time(div, data);
    }
    else {
        var dimensions       = results[scenario]['dimensions'];
        var dimension_values = results[scenario]['dimension-values'];
        var data             = results[scenario]['data'];

        if (type == 'series') {
            plot_series(div, dimensions, dimension_values, data);
        }
        else if (type == 'x-y') {
            plot_x_y(div, dimensions, dimension_values, data);
        }
        else if (type == 'r-l') {
            plot_r_l(div, dimensions, dimension_values, data);
        }
    }
}

function plot_time(div, data) {
    var show_latency = div.attr('data-latency') == 'true';
    var chart_data = [];
    var keys = show_latency
       ? ['send-msg-rate', 'recv-msg-rate', 'avg-latency']
        : ['send-msg-rate', 'recv-msg-rate'];
    $.each(keys, function(i, plot_key) {
        var d = [];
        $.each(data['samples'], function(j, sample) {
            d.push([sample['elapsed'] / 1000, sample[plot_key]]);
        });
        var yaxis = (plot_key.indexOf('latency') == -1 ? 1 : 2);
        chart_data.push({label: plot_key, data: d, yaxis: yaxis});
    });

    plot_data(div, chart_data, {yaxes: axes_rate_and_latency});
}

function plot_series(div, dimensions, dimension_values, data) {
    var x_key         = div.attr('data-x-key');
    var series_key    = div.attr('data-series-key');
    var series_first  = dimensions[0] == series_key;
    var series_values = dimension_values[series_key];
    var x_values      = dimension_values[x_key];
    var plot_key      = attr_or_default(div, 'plot-key', 'send-msg-rate');

    var chart_data = [];
    $.each(series_values, function(i, s_val) {
        var d = [];
        $.each(x_values, function(j, x_val) {
            var val = series_first ? data[s_val][x_val] :
                                     data[x_val][s_val];
            d.push([x_val, val[plot_key]]);
        });
        chart_data.push({label: series_key + ' = ' + s_val, data: d});
    });

    plot_data(div, chart_data);
}

function plot_x_y(div, dimensions, dimension_values, data) {
    var x_key = div.attr('data-x-key');
    var x_values = dimension_values[x_key];
    var plot_keys = attr_or_default(div, 'plot-keys', 'send-msg-rate').split(' ');
    var chart_data = [];
    var extra = {};
    $.each(plot_keys, function(i, plot_key) {
        var d = [];
        $.each(x_values, function(j, x_val) {
            d.push([x_val, data[x_val][plot_key]]);
        });
        var yaxis = 1;
        if (plot_key.indexOf('bytes') != -1) {
            yaxis = 2;
            extra = {yaxes: axes_rate_and_bytes};
        }
        chart_data.push({label: plot_key, data: d, yaxis: yaxis});
    });
    plot_data(div, chart_data, extra);
}

function plot_r_l(div, dimensions, dimension_values, data) {
    var x_values = dimension_values['producerRateLimit'];

    var chart_data = [];
    var d = [];
    $.each(x_values, function(i, x_val) {
        d.push([x_val, data[x_val]['send-msg-rate']]);
    });
    chart_data.push({label: 'rate achieved', data: d, yaxis: 1});

    d = [];
    $.each(x_values, function(i, x_val) {
        d.push([x_val, data[x_val]['avg-latency']]);
    });
    chart_data.push({label: 'latency (us)', data: d, yaxis: 2});

    plot_data(div, chart_data, {yaxes: axes_rate_and_latency});
}

function plot_data(div, chart_data, extra) {
    var legend     = attr_or_default(div, 'legend', 'se');
    var x_axis_log = attr_or_default(div, 'x-axis-log', 'false') == 'true';
    var cssClass   = div.attr('class');

    var chrome = {
        series: { lines: { show: true } },
        grid:   { borderWidth: 2, borderColor: "#aaa" },
        xaxis:  { tickColor: "#fff" },
        yaxis:  { tickColor: "#eee" },
        legend: { position: legend, backgroundOpacity: 0.5 }
    };

    if (div.attr('class') == 'small-chart') {
        chrome['legend'] = { show: false };
    }

    if (extra != undefined) {
        for (var k in extra) {
            chrome[k] = extra[k];
        }
    }

    if (x_axis_log) {
        chrome['xaxis'] = log_x_axis;
    }

    var cell = div.wrap('<td />').parent();;
    var row = cell.wrap('<tr/>').parent();
    row.wrap('<table class="' + cssClass + '-wrapper"/>');

    cell.before('<td class="yaxis">' + div.attr('data-y-axis') + '</td>');
    if (div.attr('data-y-axis2')) {
        cell.after('<td class="yaxis">' + div.attr('data-y-axis2') + '</td>');
    }
    row.after('<tr><td></td><td class="xaxis">' + div.attr('data-x-axis') +
              '</td><td></td></tr>');

    $.plot(div, chart_data, chrome);
}

function log_transform(v) {
    return Math.log(v);
}

function log_ticks(axis) {
    var val = axis.min;
    var res = [val];
    while (val < axis.max) {
        val *= 10;
        res.push(val);
    }
    return res;
}

function attr_or_default(div, key, def) {
    var res = div.attr('data-' + key);
    return res == undefined ? def : res;
}

var axes_rate_and_latency = [{min:       0},
                             {min:       100,
                              transform: log_transform,
                              ticks:     log_ticks,
                              position:  "right"}];

var axes_rate_and_bytes = [{min:       0},
                           {min:       0,
                            position:  "right"}];

var log_x_axis = {min:       1,
                  transform: log_transform,
                  ticks:     log_ticks};
