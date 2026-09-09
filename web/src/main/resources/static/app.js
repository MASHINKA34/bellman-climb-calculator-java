'use strict';

/* ===========================================================================
   Калькулятор набора крейсерской высоты — клиентская часть.
   Весь расчёт выполняет сервер (модуль core), здесь только форма,
   графики и таблицы.
   =========================================================================== */

const TASK_FIELDS = [
    {key: 'startAltitude', label: 'Высота начала набора', unit: 'м', step: 100, min: 0},
    {key: 'cruiseAltitude', label: 'Крейсерская высота', unit: 'м', step: 100, min: 100},
    {key: 'altitudeStep', label: 'Шаг сетки по высоте', unit: 'м', step: 50, min: 50},
    {key: 'startSpeed', label: 'Начальная истинная скорость', unit: 'м/с', step: 5, min: 5}
];

const GRID_FIELDS = [
    {key: 'speedMin', label: 'Нижняя граница скорости', unit: 'м/с', step: 5, min: 5},
    {key: 'speedMax', label: 'Верхняя граница скорости', unit: 'м/с', step: 5, min: 5},
    {key: 'speedStep', label: 'Шаг сетки по скорости', unit: 'м/с', step: 0.5, min: 0.5},
    {key: 'maxSpeedChangePerStep', label: 'Макс. изменение V за шаг', unit: 'м/с', step: 5, min: 5},
    {key: 'minRateOfClimb', label: 'Минимально допустимая Vy', unit: 'м/с', step: 0.1, min: 0}
];

const AIRCRAFT_FIELDS = [
    {key: 'mass', label: 'Полётная масса', unit: 'кг', step: 500, min: 500},
    {key: 'wingArea', label: 'Площадь крыла', unit: 'м²', step: 0.1, min: 0.1},
    {key: 'cx0', label: 'Cx0 - сопротивление при нулевой подъёмной силе', unit: '', step: 0.001, min: 0.001},
    {key: 'inducedDragFactor', label: 'A - отвал поляры', unit: '', step: 0.001, min: 0.001},
    {key: 'cyMax', label: 'Cy max', unit: '', step: 0.05, min: 0.1},
    {key: 'stallMargin', label: 'Запас по скорости сваливания', unit: '', step: 0.05, min: 1},
    {key: 'thrustSeaLevel', label: 'Тяга у земли, все двигатели', unit: 'Н', step: 5000, min: 5000},
    {key: 'thrustAltitudeExponent', label: 'n - падение тяги по высоте', unit: '', step: 0.05, min: 0.05},
    {key: 'thrustMachFactor', label: 'k_M - падение тяги по числу Маха', unit: '', step: 0.05, min: 0},
    {key: 'sfcSeaLevel', label: 'Ce₀ - удельный расход у земли', unit: 'кг/(Н·ч)', step: 0.001, min: 0.001},
    {key: 'sfcMachFactor', label: 'k_Ce - рост расхода по числу Маха', unit: '', step: 0.05, min: 0},
    {key: 'maxMach', label: 'Максимальное число Маха', unit: '', step: 0.01, min: 0.05},
    {key: 'maxIndicatedSpeed', label: 'Макс. приборная скорость', unit: 'м/с', step: 5, min: 5}
];

const CRITERIA = [
    {value: 'FUEL', label: 'Минимум расхода топлива'},
    {value: 'TIME', label: 'Минимум времени набора'},
    {value: 'COST_INDEX', label: 'Минимум приведённых затрат (cost index)'}
];

const STORAGE_KEY = 'bellman-climb-input-v1';

let defaults = null;
let lastResult = null;
const charts = [];

/* ============================ мелкие утилиты ============================ */

function $(selector, root) {
    return (root || document).querySelector(selector);
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function fmt(value, digits) {
    if (value === null || value === undefined || !isFinite(value)) {
        return '-';
    }
    const d = digits === undefined ? 1 : digits;
    return value.toLocaleString('ru-RU', {minimumFractionDigits: d, maximumFractionDigits: d});
}

function fmtClock(seconds) {
    const total = Math.round(seconds);
    return Math.floor(total / 60) + ':' + String(total % 60).padStart(2, '0');
}

function debounce(fn, delay) {
    let timer = null;
    return function () {
        clearTimeout(timer);
        timer = setTimeout(fn, delay);
    };
}

/** Согласование существительного с числительным: 1 узел, 2 узла, 5 узлов. */
function plural(count, one, few, many) {
    const mod100 = Math.abs(count) % 100;
    const mod10 = mod100 % 10;
    if (mod100 >= 11 && mod100 <= 14) {
        return many;
    }
    if (mod10 === 1) {
        return one;
    }
    if (mod10 >= 2 && mod10 <= 4) {
        return few;
    }
    return many;
}

/** Наибольшее значение функции Беллмана — по нему нормируется тепловая заливка. */
function matrixMaximum(matrix) {
    let maximum = 0;
    matrix.forEach(function (row) {
        row.forEach(function (value) {
            if (value !== null && value > maximum) {
                maximum = value;
            }
        });
    });
    return maximum;
}

/* ============================ построение формы ============================ */

function numberField(spec, value) {
    const wrapper = document.createElement('div');
    wrapper.className = 'field';

    const label = document.createElement('label');
    label.setAttribute('for', 'f-' + spec.key);
    label.innerHTML = escapeHtml(spec.label)
        + (spec.unit ? ' <span class="unit">' + escapeHtml(spec.unit) + '</span>' : '');

    const input = document.createElement('input');
    input.type = 'number';
    input.id = 'f-' + spec.key;
    input.dataset.key = spec.key;
    input.step = String(spec.step);
    if (spec.min !== undefined) {
        input.min = String(spec.min);
    }
    input.value = String(value);

    wrapper.appendChild(label);
    wrapper.appendChild(input);
    return wrapper;
}

function buildForm(data) {
    const taskBox = $('#fs-task');
    const gridBox = $('#fs-grid');
    const aircraftBox = $('#fs-aircraft');
    taskBox.querySelectorAll('.field, .check, select, .criterion').forEach(function (node) {
        node.remove();
    });
    gridBox.innerHTML = '<legend>Сетка состояний и ограничения</legend>';
    aircraftBox.innerHTML = '';

    TASK_FIELDS.forEach(function (spec) {
        taskBox.appendChild(numberField(spec, data.task[spec.key]));
    });

    // критерий оптимальности
    const criterionField = document.createElement('div');
    criterionField.className = 'field wide criterion';
    const criterionLabel = document.createElement('label');
    criterionLabel.setAttribute('for', 'f-criterion');
    criterionLabel.textContent = 'Критерий оптимальности';
    const select = document.createElement('select');
    select.id = 'f-criterion';
    CRITERIA.forEach(function (option) {
        const node = document.createElement('option');
        node.value = option.value;
        node.textContent = option.label;
        select.appendChild(node);
    });
    select.value = data.task.criterion;
    criterionField.appendChild(criterionLabel);
    criterionField.appendChild(select);
    taskBox.appendChild(criterionField);

    // стоимость времени — нужна только для комбинированного критерия
    const costField = numberField(
        {key: 'costIndexKgPerMinute', label: 'Стоимость времени', unit: 'кг/мин', step: 1, min: 0},
        data.task.costIndexKgPerMinute);
    costField.id = 'cost-index-field';
    taskBox.appendChild(costField);

    // крейсерское число Маха — необязательное условие
    const machToggle = document.createElement('label');
    machToggle.className = 'check';
    const machCheckbox = document.createElement('input');
    machCheckbox.type = 'checkbox';
    machCheckbox.id = 'f-mach-enabled';
    machCheckbox.checked = data.task.targetCruiseMach !== null
        && data.task.targetCruiseMach !== undefined;
    machToggle.appendChild(machCheckbox);
    machToggle.appendChild(document.createTextNode('Задать крейсерское число Маха'));
    taskBox.appendChild(machToggle);

    const machField = numberField(
        {key: 'targetCruiseMach', label: 'Крейсерское число Маха', unit: '', step: 0.01, min: 0.05},
        data.task.targetCruiseMach === null || data.task.targetCruiseMach === undefined
            ? 0.78 : data.task.targetCruiseMach);
    machField.id = 'mach-field';
    taskBox.appendChild(machField);

    const toleranceField = numberField(
        {key: 'cruiseMachTolerance', label: 'Допуск по числу Маха', unit: '±', step: 0.005, min: 0.005},
        data.task.cruiseMachTolerance);
    toleranceField.id = 'mach-tolerance-field';
    taskBox.appendChild(toleranceField);

    GRID_FIELDS.forEach(function (spec) {
        gridBox.appendChild(numberField(spec, data.task[spec.key]));
    });

    AIRCRAFT_FIELDS.forEach(function (spec) {
        aircraftBox.appendChild(numberField(spec, data.aircraft[spec.key]));
    });

    select.addEventListener('change', syncConditionalFields);
    machCheckbox.addEventListener('change', syncConditionalFields);
    syncConditionalFields();
}

/** Прячет поля, не относящиеся к выбранному режиму. */
function syncConditionalFields() {
    const costVisible = $('#f-criterion').value === 'COST_INDEX';
    $('#cost-index-field').hidden = !costVisible;

    const machVisible = $('#f-mach-enabled').checked;
    $('#mach-field').hidden = !machVisible;
    $('#mach-tolerance-field').hidden = !machVisible;
}

function readForm() {
    const task = {};
    TASK_FIELDS.concat(GRID_FIELDS).forEach(function (spec) {
        task[spec.key] = Number($('#f-' + spec.key).value);
    });
    task.criterion = $('#f-criterion').value;
    task.costIndexKgPerMinute = Number($('#f-costIndexKgPerMinute').value);
    task.targetCruiseMach = $('#f-mach-enabled').checked
        ? Number($('#f-targetCruiseMach').value) : null;
    task.cruiseMachTolerance = Number($('#f-cruiseMachTolerance').value);

    const aircraft = {name: defaults.aircraft.name};
    AIRCRAFT_FIELDS.forEach(function (spec) {
        aircraft[spec.key] = Number($('#f-' + spec.key).value);
    });

    return {aircraft: aircraft, task: task};
}

function saveInput(input) {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(input));
    } catch (ignored) {
        /* приватный режим или запрет на хранение — не повод ломать расчёт */
    }
}

function loadInput() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch (ignored) {
        return null;
    }
}

/* ============================ графики ============================ */

function niceTicks(min, max, count) {
    if (!(max > min)) {
        return [min];
    }
    const raw = (max - min) / count;
    const magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
    const normalized = raw / magnitude;
    let multiplier = 1;
    if (normalized > 5) {
        multiplier = 10;
    } else if (normalized > 2) {
        multiplier = 5;
    } else if (normalized > 1) {
        multiplier = 2;
    }
    const step = multiplier * magnitude;
    const ticks = [];
    for (let value = Math.ceil(min / step) * step; value <= max + step * 1e-6; value += step) {
        ticks.push(Math.abs(value) < step * 1e-6 ? 0 : value);
    }
    return ticks;
}

function registerChart(host, draw) {
    charts.push({host: host, draw: draw});
    draw(host);
}

window.addEventListener('resize', debounce(function () {
    charts.forEach(function (chart) {
        chart.draw(chart.host);
    });
}, 160));

function chartFrame(host, spec) {
    const width = Math.max(300, host.clientWidth || 300);
    const height = spec.height || 280;
    const pad = {l: 58, r: 16, t: 12, b: 38};

    // вырожденный диапазон (все значения совпали) растягивается, иначе делили бы на ноль
    const span = function (min, max) {
        return max > min ? [min, max] : [min - 0.5, max + 0.5];
    };
    const xRange = span(spec.xMin, spec.xMax);
    const yRange = span(spec.yMin, spec.yMax);
    const xMin = xRange[0];
    const xMax = xRange[1];
    const yMin = yRange[0];
    const yMax = yRange[1];

    const x = function (value) {
        return pad.l + (value - xMin) / (xMax - xMin) * (width - pad.l - pad.r);
    };
    const y = function (value) {
        return height - pad.b - (value - yMin) / (yMax - yMin) * (height - pad.t - pad.b);
    };

    let body = '';
    niceTicks(yMin, yMax, 5).forEach(function (tick) {
        const py = y(tick);
        body += '<line x1="' + pad.l + '" y1="' + py + '" x2="' + (width - pad.r) + '" y2="' + py
            + '" style="stroke:var(--grid-line);stroke-width:1"/>'
            + '<text x="' + (pad.l - 8) + '" y="' + (py + 3.5)
            + '" text-anchor="end" style="fill:var(--text-faint);font-size:11px">'
            + escapeHtml(spec.formatY(tick)) + '</text>';
    });
    niceTicks(xMin, xMax, 6).forEach(function (tick) {
        const px = x(tick);
        body += '<line x1="' + px + '" y1="' + pad.t + '" x2="' + px + '" y2="' + (height - pad.b)
            + '" style="stroke:var(--grid-line);stroke-width:1"/>'
            + '<text x="' + px + '" y="' + (height - pad.b + 16)
            + '" text-anchor="middle" style="fill:var(--text-faint);font-size:11px">'
            + escapeHtml(spec.formatX(tick)) + '</text>';
    });

    body += '<text x="' + ((pad.l + width - pad.r) / 2) + '" y="' + (height - 5)
        + '" text-anchor="middle" style="fill:var(--text-dim);font-size:11.5px">'
        + escapeHtml(spec.xLabel) + '</text>'
        + '<text transform="translate(14,' + ((pad.t + height - pad.b) / 2)
        + ') rotate(-90)" text-anchor="middle" style="fill:var(--text-dim);font-size:11.5px">'
        + escapeHtml(spec.yLabel) + '</text>';

    return {width: width, height: height, pad: pad, x: x, y: y, body: body};
}

function drawLineChart(host, spec) {
    const all = spec.series.reduce(function (acc, series) {
        return acc.concat(series.points);
    }, []);
    if (!all.length) {
        host.innerHTML = '';
        return;
    }
    const xs = all.map(function (p) {
        return p[0];
    });
    const ys = all.map(function (p) {
        return p[1];
    });

    const frame = chartFrame(host, Object.assign({}, spec, {
        xMin: Math.min.apply(null, xs),
        xMax: Math.max.apply(null, xs),
        yMin: spec.yZero ? 0 : Math.min.apply(null, ys),
        yMax: Math.max.apply(null, ys)
    }));

    let body = frame.body;
    spec.series.forEach(function (series) {
        const path = series.points.map(function (point, index) {
            return (index ? 'L' : 'M') + frame.x(point[0]) + ' ' + frame.y(point[1]);
        }).join(' ');
        body += '<path d="' + path + '" fill="none" style="stroke:' + series.color
            + ';stroke-width:2;stroke-linejoin:round;stroke-linecap:round'
            + (series.dashed ? ';stroke-dasharray:5 4' : '') + '"/>';
        if (series.dots) {
            series.points.forEach(function (point) {
                body += '<circle cx="' + frame.x(point[0]) + '" cy="' + frame.y(point[1])
                    + '" r="2.6" style="fill:' + series.color + '"/>';
            });
        }
    });

    host.innerHTML = '<svg width="' + frame.width + '" height="' + frame.height
        + '" viewBox="0 0 ' + frame.width + ' ' + frame.height + '">' + body + '</svg>';
}

/** Плоскость состояний (V, H): область допустимых режимов, «цена» узла и оптимальная траектория. */
function drawStateGrid(host, result) {
    const speeds = result.speeds;
    const altitudes = result.altitudes;

    const frame = chartFrame(host, {
        height: 340,
        xMin: speeds[0],
        xMax: speeds[speeds.length - 1],
        yMin: altitudes[0],
        yMax: altitudes[altitudes.length - 1],
        xLabel: 'Истинная скорость V, м/с',
        yLabel: 'Высота H, м',
        formatX: function (v) {
            return fmt(v, 0);
        },
        formatY: function (v) {
            return fmt(v, 0);
        }
    });

    // на подробных сетках сервер не присылает таблицу функции Беллмана —
    // тогда узлы раскрашиваются только по признаку допустимости
    const values = result.valueFunction;
    const maxValue = values ? matrixMaximum(values) : 0;

    const cellWidth = Math.max(3, (frame.width - frame.pad.l - frame.pad.r) / speeds.length - 1.5);
    const cellHeight = Math.max(3, (frame.height - frame.pad.t - frame.pad.b) / altitudes.length - 1.5);

    let body = frame.body;
    if (values || result.feasible) {
        for (let k = 0; k < altitudes.length; k++) {
            for (let i = 0; i < speeds.length; i++) {
                const cx = frame.x(speeds[i]);
                const cy = frame.y(altitudes[k]);
                const value = values ? values[k][i] : null;
                const reachable = values ? value !== null : result.feasible[k][i];
                if (!reachable) {
                    body += '<circle cx="' + cx + '" cy="' + cy
                        + '" r="1.1" style="fill:var(--text-faint);opacity:.35"/>';
                } else {
                    const intensity = values && maxValue > 0 ? value / maxValue : 0.5;
                    body += '<rect x="' + (cx - cellWidth / 2) + '" y="' + (cy - cellHeight / 2)
                        + '" width="' + cellWidth + '" height="' + cellHeight
                        + '" rx="1.5" style="fill:var(--accent);opacity:'
                        + (0.10 + 0.55 * intensity).toFixed(3) + '"/>';
                }
            }
        }
    }

    const path = result.trajectory.map(function (point, index) {
        return (index ? 'L' : 'M') + frame.x(point.speed) + ' ' + frame.y(point.altitude);
    }).join(' ');
    body += '<path d="' + path + '" fill="none" style="stroke:var(--accent);stroke-width:2.4;'
        + 'stroke-linejoin:round;stroke-linecap:round"/>';
    result.trajectory.forEach(function (point) {
        body += '<circle cx="' + frame.x(point.speed) + '" cy="' + frame.y(point.altitude)
            + '" r="3.1" style="fill:var(--surface);stroke:var(--accent);stroke-width:2"/>';
    });

    host.innerHTML = '<svg width="' + frame.width + '" height="' + frame.height
        + '" viewBox="0 0 ' + frame.width + ' ' + frame.height + '">' + body + '</svg>';
}

/* ============================ вывод результата ============================ */

function chartBlock(id, title, hint, legend) {
    return '<div class="chart">'
        + '<h3>' + escapeHtml(title) + '</h3>'
        + '<p class="chart-hint">' + escapeHtml(hint) + '</p>'
        + '<div class="chart-canvas" id="' + id + '"></div>'
        + (legend ? '<div class="legend">' + legend + '</div>' : '')
        + '</div>';
}

function trajectoryTable(result) {
    const head = ['Этап', 'H, м', 'V, м/с', 'M', 'Vпр, м/с', 'Vy, м/с', 'Cy',
        'P, кН', 'Q, кН', 'Δt, с', 'Δm, кг', 't, мин', 'm, кг', 'L, км'];

    let rows = '';
    result.trajectory.forEach(function (p) {
        rows += '<tr>'
            + '<td>' + p.step + '</td>'
            + '<td>' + fmt(p.altitude, 0) + '</td>'
            + '<td>' + fmt(p.speed, 0) + '</td>'
            + '<td>' + fmt(p.mach, 3) + '</td>'
            + '<td>' + fmt(p.indicatedSpeed, 0) + '</td>'
            + '<td>' + (p.step === 0 ? '-' : fmt(p.rateOfClimb, 2)) + '</td>'
            + '<td>' + fmt(p.liftCoefficient, 3) + '</td>'
            + '<td>' + fmt(p.thrust / 1000, 1) + '</td>'
            + '<td>' + fmt(p.drag / 1000, 1) + '</td>'
            + '<td>' + (p.step === 0 ? '-' : fmt(p.segmentTime, 1)) + '</td>'
            + '<td>' + (p.step === 0 ? '-' : fmt(p.segmentFuel, 1)) + '</td>'
            + '<td>' + fmt(p.time / 60, 2) + '</td>'
            + '<td>' + fmt(p.fuel, 1) + '</td>'
            + '<td>' + fmt(p.distance / 1000, 1) + '</td>'
            + '</tr>';
    });

    return '<div class="table-scroll"><table><thead><tr>'
        + head.map(function (title) {
            return '<th>' + escapeHtml(title) + '</th>';
        }).join('')
        + '</tr></thead><tbody>' + rows + '</tbody></table></div>';
}

function valueFunctionTable(result) {
    const speeds = result.speeds;
    const altitudes = result.altitudes;

    const onPath = {};
    result.trajectory.forEach(function (point) {
        for (let i = 0; i < speeds.length; i++) {
            if (Math.abs(speeds[i] - point.speed) < 1e-9) {
                onPath[point.step + ':' + i] = true;
            }
        }
    });

    const maxValue = matrixMaximum(result.valueFunction);

    let head = '<tr><th class="corner">H, м \\ V, м/с</th>';
    speeds.forEach(function (speed) {
        head += '<th>' + fmt(speed, 0) + '</th>';
    });
    head += '</tr>';

    let rows = '';
    for (let k = altitudes.length - 1; k >= 0; k--) {
        rows += '<tr><th class="corner">' + fmt(altitudes[k], 0) + '</th>';
        for (let i = 0; i < speeds.length; i++) {
            const value = result.valueFunction[k][i];
            if (value === null) {
                rows += '<td class="dead">-</td>';
            } else {
                const intensity = maxValue > 0 ? value / maxValue : 0;
                const marked = onPath[k + ':' + i] ? ' path' : '';
                rows += '<td class="value' + marked + '" style="background-color:rgba(var(--heat-rgb),'
                    + (0.05 + 0.28 * intensity).toFixed(3) + ')">'
                    + fmt(value, value >= 100 ? 0 : 1) + '</td>';
            }
        }
        rows += '</tr>';
    }

    return '<div class="table-scroll matrix"><table><thead>' + head
        + '</thead><tbody>' + rows + '</tbody></table></div>';
}

function renderResult(result) {
    lastResult = result;
    const criterionValueDigits = result.criterion === 'TIME' ? 0 : 1;

    let notes = '';
    if (result.notes && result.notes.length) {
        notes = '<div class="notice">' + result.notes.map(function (note) {
            return '<p>' + escapeHtml(note) + '</p>';
        }).join('') + '</div>';
    }

    const html = ''
        + '<div class="card">'
        + '  <h2>Результат: ' + escapeHtml(result.criterionTitle) + '</h2>'
        + '  <div class="card-body">'
        + '    <div class="summary">'
        + statCard('Время набора', fmtClock(result.totalTime), 'мин:с')
        + statCard('Расход топлива', fmt(result.totalFuel, 1), 'кг')
        + statCard('Дальность набора', fmt(result.totalDistance / 1000, 1), 'км')
        + statCard('Функция Беллмана f₀', fmt(result.optimalValue, criterionValueDigits),
            result.criterionUnit, true)
        + '    </div>'
        + notes
        + '  </div>'
        + '</div>'

        + '<div class="card">'
        + '  <h2>Графики</h2>'
        + '  <div class="card-body">'
        + '    <div class="charts">'
        + chartBlock('chart-profile-time', 'Профиль набора по времени',
            'Высота полёта в зависимости от времени от начала набора.')
        + chartBlock('chart-profile-range', 'Профиль набора по дальности',
            'Высота полёта в зависимости от пройденного расстояния.')
        + chartBlock('chart-grid', 'Плоскость состояний (V, H)',
            'Заливка узла тем плотнее, чем больше остаток затрат до крейсерской высоты. '
            + 'Точками показаны состояния, из которых набор невозможен.',
            '<span><i style="background:var(--accent)"></i>оптимальная траектория</span>'
            + '<span><i class="dot" style="background:var(--accent);opacity:.45"></i>допустимый узел</span>'
            + '<span><i class="dot" style="background:var(--text-faint);opacity:.5"></i>набор невозможен</span>')
        + chartBlock('chart-vy', 'Вертикальная скорость набора',
            'Vy на каждом участке траектории: с высотой избыток тяги падает и набор замедляется.')
        + '    </div>'
        + '  </div>'
        + '</div>'

        + '<div class="card">'
        + '  <div class="card-body">'
        + '    <div class="toolbar">'
        + '      <span class="title">Оптимальная траектория набора</span>'
        + '      <button type="button" class="btn-ghost" id="btn-csv">Скачать CSV</button>'
        + '    </div>'
        + trajectoryTable(result)
        + (result.valueFunction
            ? '    <details class="section">'
            + '      <summary>Функция Беллмана по всем узлам сетки</summary>'
            + '      <div class="details-body">'
            + '        <p class="chart-hint">f(H, V) - минимальные затраты на путь от узла до '
            + 'крейсерской высоты, ' + escapeHtml(result.criterionUnit)
            + '. Обведены узлы оптимальной траектории, прочерк - состояние, '
            + 'из которого крейсерская высота недостижима.</p>'
            + valueFunctionTable(result)
            + '      </div>'
            + '    </details>'
            : '')
        + '    <div class="meta">'
        + 'Сетка: ' + result.altitudes.length + ' '
        + plural(result.altitudes.length, 'высотный уровень', 'высотных уровня', 'высотных уровней')
        + ' × ' + result.speeds.length + ' '
        + plural(result.speeds.length, 'скорость', 'скорости', 'скоростей')
        + ', допустимых узлов - ' + result.feasibleNodes
        + '. Просмотрено переходов: ' + result.evaluatedTransitions.toLocaleString('ru-RU')
        + '. Время счёта: ' + result.solveTimeMillis + ' мс.'
        + '</div>'
        + '  </div>'
        + '</div>';

    $('#results').innerHTML = html;
    charts.length = 0;

    const trajectory = result.trajectory;

    registerChart($('#chart-profile-time'), function (host) {
        drawLineChart(host, {
            height: 260,
            xLabel: 'Время t, мин',
            yLabel: 'Высота H, м',
            formatX: function (v) {
                return fmt(v, 0);
            },
            formatY: function (v) {
                return fmt(v, 0);
            },
            series: [{
                color: 'var(--accent)',
                dots: true,
                points: trajectory.map(function (p) {
                    return [p.time / 60, p.altitude];
                })
            }]
        });
    });

    registerChart($('#chart-profile-range'), function (host) {
        drawLineChart(host, {
            height: 260,
            xLabel: 'Дальность L, км',
            yLabel: 'Высота H, м',
            formatX: function (v) {
                return fmt(v, 0);
            },
            formatY: function (v) {
                return fmt(v, 0);
            },
            series: [{
                color: 'var(--accent)',
                dots: true,
                points: trajectory.map(function (p) {
                    return [p.distance / 1000, p.altitude];
                })
            }]
        });
    });

    registerChart($('#chart-grid'), function (host) {
        drawStateGrid(host, result);
    });

    registerChart($('#chart-vy'), function (host) {
        drawLineChart(host, {
            height: 260,
            yZero: true,
            xLabel: 'Высота H, м',
            yLabel: 'Вертикальная скорость Vy, м/с',
            formatX: function (v) {
                return fmt(v, 0);
            },
            formatY: function (v) {
                return fmt(v, 1);
            },
            series: [{
                color: 'var(--accent)',
                dots: true,
                points: trajectory.slice(1).map(function (p) {
                    return [p.altitude, p.rateOfClimb];
                })
            }]
        });
    });

    $('#btn-csv').addEventListener('click', downloadCsv);
}

function statCard(label, value, unit, accent) {
    return '<div class="stat' + (accent ? ' accent' : '') + '">'
        + '<div class="stat-label">' + escapeHtml(label) + '</div>'
        + '<div class="stat-value">' + escapeHtml(value)
        + '<span>' + escapeHtml(unit) + '</span></div>'
        + '</div>';
}

function showError(title, detail) {
    $('#results').innerHTML = '<div class="card"><div class="card-body">'
        + '<div class="error"><strong>' + escapeHtml(title) + '</strong>'
        + escapeHtml(detail || '') + '</div></div></div>';
    charts.length = 0;
}

/* ============================ выгрузка в CSV ============================ */

function csvNumber(value, digits) {
    return value.toFixed(digits).replace('.', ',');
}

function downloadCsv() {
    if (!lastResult) {
        return;
    }
    const header = ['Этап', 'H, м', 'V, м/с', 'M', 'Vпр, м/с', 'Hэ, м', 'Vy, м/с', 'Cy',
        'P, Н', 'Q, Н', 'dP, Н', 'q, кг/с', 'dt, с', 'dm, кг', 'dL, м', 't, с', 'm, кг', 'L, м'];

    const lines = [header.join(';')];
    lastResult.trajectory.forEach(function (p) {
        lines.push([
            p.step,
            csvNumber(p.altitude, 0),
            csvNumber(p.speed, 1),
            csvNumber(p.mach, 4),
            csvNumber(p.indicatedSpeed, 1),
            csvNumber(p.energyAltitude, 0),
            csvNumber(p.rateOfClimb, 3),
            csvNumber(p.liftCoefficient, 4),
            csvNumber(p.thrust, 0),
            csvNumber(p.drag, 0),
            csvNumber(p.excessThrust, 0),
            csvNumber(p.fuelFlow, 4),
            csvNumber(p.segmentTime, 2),
            csvNumber(p.segmentFuel, 2),
            csvNumber(p.segmentDistance, 0),
            csvNumber(p.time, 2),
            csvNumber(p.fuel, 2),
            csvNumber(p.distance, 0)
        ].join(';'));
    });

    const blob = new Blob(['﻿' + lines.join('\r\n')], {type: 'text/csv;charset=utf-8'});
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = 'nabor-krejserskoj-vysoty.csv';
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(link.href);
}

/* ============================ запуск ============================ */

async function solve(event) {
    if (event) {
        event.preventDefault();
    }
    const button = $('#btn-solve');
    button.disabled = true;
    button.textContent = 'Считаю…';

    const input = readForm();
    saveInput(input);

    try {
        const response = await fetch('api/climb', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(input)
        });
        const payload = await response.json();
        if (!response.ok) {
            showError(payload.title || 'Ошибка расчёта', payload.detail);
        } else {
            renderResult(payload);
            if (window.matchMedia('(max-width: 999px)').matches) {
                $('#results').scrollIntoView({behavior: 'smooth', block: 'start'});
            }
        }
    } catch (error) {
        showError('Сервер недоступен',
            'Не удалось связаться с расчётным модулем: ' + error.message);
    } finally {
        button.disabled = false;
        button.textContent = 'Рассчитать';
    }
}

async function init() {
    const response = await fetch('api/defaults');
    defaults = await response.json();

    const saved = loadInput();
    buildForm(saved && saved.task && saved.aircraft ? saved : defaults);

    $('#form').addEventListener('submit', solve);
    $('#btn-reset').addEventListener('click', function () {
        try {
            localStorage.removeItem(STORAGE_KEY);
        } catch (ignored) {
            /* пусто */
        }
        buildForm(defaults);
    });

    solve();
}

init();
