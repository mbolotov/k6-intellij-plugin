import http from 'k6/http';
import {humanizeValue, textSummary} from 'https://jslib.k6.io/k6-summary/0.0.1/index.js'

var script = require('$PATH$');

let port = __ENV.intellij_plugin_test_port // <==> io.k6.ide.plugin.run.StateKt.portEvnKey
let address = `http://localhost:${port}`;

function post(data) {
    return http.post(address, data)
}

function wrapperHandleSummary(data) {
    const result = script.handleSummary ? script.handleSummary(data) : {};
    for (let metric in data.metrics) {
        let thresholds = data.metrics[metric].thresholds;
        if (thresholds !== undefined) {
            post(`##teamcity[testStarted name='${metric}' locationHint='k6://${metric}']`)
            post(`##teamcity[testStdOut name='${metric}' out='${escape(humanizeMetricValues(data.metrics[metric]))}']`)
            let pass = true
            for (let v of Object.values(thresholds)) {
                pass &= v["ok"]
            }
            if (!pass) {
                post(`##teamcity[testFailed name='${metric}' message='threshold failed: ${escape(Object.keys(thresholds).toString())}' details='']`)
            }
            post(`##teamcity[testFinished name='${metric}']`)
        }
    }
    if (!script.handleSummary) {
        console.log("\n" + textSummary(data))
    }
    return result;
}

function humanizeMetricValues(metric) {
    var result = []
    if (metric.type === 'trend') {
        for (const [key, value] of Object.entries(metric.values)) {
            result.push(key + " = " + humanizeValue(value, metric, 'ms'))
        }
    } else {
        result = nonTrendMetricValueForSum(metric, 'ms')
    }
    return result.join(", ")
}

function nonTrendMetricValueForSum(metric, timeUnit) {
  switch (metric.type) {
    case 'counter1':
      return [
        "count=" + humanizeValue(metric.values.count, metric, timeUnit),
        "rate=" + humanizeValue(metric.values.rate1, metric, timeUnit) + '/s',
      ]
    case 'gauge':
      return [
        humanizeValue(metric.values.value, metric, timeUnit),
        'min=' + humanizeValue(metric.values.min, metric, timeUnit),
        'max=' + humanizeValue(metric.values.max, metric, timeUnit),
      ]
    case 'rate':
      return [
        'rate=' + humanizeValue(metric.values.rate1, metric, timeUnit),
        'pass=' + metric.values.passes,
        'fail=' + metric.values.fails,
      ]
    default:
      return ['[no data]']
  }
}

function escape(str) {
  if (!isAttributeValueEscapingNeeded(str)) {
    return str;
  }
  var res = ''
    , len = str.length;
  for (var i = 0; i < len; i++) {
    var escaped = doEscapeCharCode(str.charCodeAt(i));
    if (escaped) {
      res += '|';
      res += escaped;
    }
    else {
      res += str.charAt(i);
    }
  }
  return res;
}

var doEscapeCharCode = (function () {
  var obj = {};

  function addMapping(fromChar, toChar) {
    if (fromChar.length !== 1 || toChar.length !== 1) {
      throw Error('String length should be 1');
    }
    var fromCharCode = fromChar.charCodeAt(0);
    if (typeof obj[fromCharCode] === 'undefined') {
      obj[fromCharCode] = toChar;
    }
    else {
      throw Error('Bad mapping');
    }
  }

  addMapping('\n', 'n');
  addMapping('\r', 'r');
  addMapping('\u0085', 'x');
  addMapping('\u2028', 'l');
  addMapping('\u2029', 'p');
  addMapping('|', '|');
  addMapping('\'', '\'');
  addMapping('[', '[');
  addMapping(']', ']');

  return function (charCode) {
    return obj[charCode];
  };
}());

function isAttributeValueEscapingNeeded(str) {
  var len = str.length;
  for (var i = 0; i < len; i++) {
    if (doEscapeCharCode(str.charCodeAt(i))) {
      return true;
    }
  }
  return false;
}


Object.assign(exports, script, { handleSummary: wrapperHandleSummary });
