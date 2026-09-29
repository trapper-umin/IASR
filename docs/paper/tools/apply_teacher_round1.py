# -*- coding: utf-8 -*-
import sys
sys.path.insert(0, __import__('os').path.dirname(__file__))
from track import Doc

src = sys.argv[1]
d = Doc(src)
P = d.find

# ================================================================ header
p = P('Рычихина Наталья Сергеевна (ORCID')
d.replace(p, '424Х', '424X')                       # Cyrillic Х -> Latin X
p = P('A.N. Bobkov, O.V. Sizova')
d.replace(p, 'A.N. Bobkov, O.V. Sizova', 'A.N. Bobkov, O.V. Sizova, N.S. Rychikhina')
p = P('Rychikhina Nataly Sergeevna (ORCID')
d.replace(p, '(ORCID 0000-0001-9872-424Х)', '(ORCID: 0000-0001-9872-424X)')

# ================================================================ body
p = P('виртуальные потоки платформы Java')
d.replace(p, ' [3],', ', а асинхронная и событийно-ориентированная обработка повышает масштабируемость высоконагруженных систем [3, 4],')
d.replace(p, '[4]', '[5]')

p = P('Библиотека Netflix concurrency-limits')
d.replace(p, ' [5]', '')
d.replace(p, ' [6].', '.')
d.replace(p, '[7]', '[6]')
d.replace(p, '[8]', '[7]')

p = P('Программные системы с обратной связью рассматриваются')
d.replace(p, 'Адаптация сложных систем как самостоятельный предмет исследования разработана в отечественной литературе [9]. ', '')
d.replace(p, ' [10]', '')
d.replace(p, ' вычислительными системами [11]. Разрабатываются методы синтеза регуляторов программных систем с формальными гарантиями [12].',
          ', включая синтез регуляторов с формальными гарантиями [8].')
d.replace(p, '[13]', '[9]')

p = P('Однорессурсный регулятор такого переноса не наблюдает')
d.replace(p, 'СУБД и соединения с ней нередко оказываются узким местом, препятствующим линейному масштабированию распределённых систем [14].',
          'база данных и соединения с ней нередко становятся источником проблем: избыточный пул соединений вызывает всплески времени отклика, которые горизонтальное масштабирование не устраняет [2], а общая для нескольких сервисов база данных может приводить к каскадным сбоям [10].')

p = P('время ожидания в очереди растёт нелинейно')
d.replace(p, ' [15], что подтверждается и исследованиями времени отклика облачных систем [16].', '.')

p = P('Связь наблюдаемых величин задаётся законом Литтла')
d.replace(p, ' [17]:', ':')

p = P('что соответствует схеме MAPE-K')
d.replace(p, '[10].', '[8].')

p = P('Асимметрия наследуется от алгоритмов')
d.replace(p, 'наследуется от алгоритмов управления перегрузкой в сетях, для которых показано, что сочетание аддитивного роста с мультипликативным снижением обеспечивает сходимость к эффективному и справедливому распределению канала между потоками, тогда как схемы с аддитивным снижением или мультипликативным ростом к справедливому распределению не сходятся [18]; для одиночного контура этот результат служит',
          'заимствована у алгоритмов управления перегрузкой в сетях передачи данных; для одиночного контура она служит')

p = P('Имитационное моделирование широко применяется')
d.replace(p, '[19]', '[11]')

p = P('Положение максимума совпало с числом выделенных ядер')
d.replace(p, ', – что согласуется с моделью сервера баз данных как многоканальной системы массового обслуживания, число каналов которой определяется числом процессоров [20].', '.')
d.replace(p, '[21]', '[12]')

p = P('Достоверность оценивалась бутстрэпом')
d.replace(p, ' [22]:', ':')

p = P('service mesh – по экспериментальным данным')
d.replace(p, '[23]', '[13]')

p = P('Направлением дальнейших исследований')
d.replace(p, 'Такой переход соответствует классической постановке адаптации и обучения в автоматических системах [24], а методы машинного обучения уже применяются для прогнозирования трафика информационных ресурсов [25],',
          'Методы машинного обучения уже применяются для прогнозирования в самых разных предметных областях – трафика информационных ресурсов [14], региональной инфляции [15], модных трендов [16], а методы анализа данных – для оптимизации онлайн-продаж [17] и в банковской сфере [18],')

# ================================================================ reference lists
RU_NEW = {
    'glumov': '3. Глумов, К.С. Преимущества реактивного подхода в высоконагруженных микросервисных системах / К.С. Глумов // Вестник науки. – 2024. – № 10(79). – С. 410–426.',
    'karpovich': '4. Карпович, М.Н. Особенности проектирования микросервисно-событийных архитектур для высоконагруженных распределенных систем обработки информаций / М.Н. Карпович // Труды БГТУ. Сер. 3, Физико-математические науки и информатика. – 2023. – № 1(266). – С. 89–95. – DOI 10.52065/2520-6141-2023-266-1-15',
    'weyns': '8. Weyns D. An Introduction to Self-Adaptive Systems: A Contemporary Software Engineering Perspective. – Hoboken: Wiley-IEEE Press, 2020. – DOI 10.1002/9781119574910',
    'ziborev': '10. Зиборев, А.В. Антипаттерны построения микросервисных приложений в высоконагруженных проектах / А.В. Зиборев // Universum: технические науки. – 2023. – № 11-1(116). – С. 29–34. – DOI 10.32743/UniTech.2023.116.11.16204',
    'astr': '15. Астраханцева, И.А. Прогнозирование региональной инфляции с помощью алгоритмов машинного обучения / И.А. Астраханцева, А.С. Герасимов, Р.Г. Астраханцев // Известия высших учебных заведений. Серия: Экономика, финансы и управление производством. – 2022. – № 4(54). – С. 6–13. – DOI 10.6060/ivecofin.2022544.620. – EDN ITYDFE.',
    'fashion': '16. Сизова, О.В. Прогнозирование модных трендов на основе нейронных сетей / О.В. Сизова, А.С. Сироткина, Н.С. Рычихина // Современные наукоёмкие технологии. Региональное приложение. – 2025. – № 3(83). – С. 47–56. – DOI 10.6060/snt.20258303',
    'pavlova': '17. Павлова, Е.А. Анализ и оптимизация on-line продаж торговой организации / Е.А. Павлова, О.В. Сизова // Известия высших учебных заведений. Серия: Экономика, финансы и управление производством. – 2024. – № 4(62). – С. 88–97. – DOI 10.6060/ivecofin.2024624.706',
    'kseno': '18. Ксенофонтова, О.Л. Использование методов интеллектуального анализа данных в банковской сфере / О.Л. Ксенофонтова, А.А. Миролюбова, С.А. Фокин // Современные наукоёмкие технологии. Региональное приложение. – 2023. – № 4(76). – С. 76–83. – DOI 10.6060/snt.20237604.00010',
}
EN_NEW = {
    'glumov': '3. Glumov K.S. Advantages of the reactive approach in high-load microservice systems. Vestnik nauki [Bulletin of Science], 2024, no. 10(79), pp. 410–426 (in Russian).',
    'karpovich': '4. Karpovich M.N. Features of designing microservice-event architectures for highly loaded distributed information processing systems. Proceedings of BSTU, issue 3, Physics and Mathematics. Informatics, 2023, no. 1(266), pp. 89–95. DOI 10.52065/2520-6141-2023-266-1-15 (in Russian).',
    'weyns': '8. Weyns D. An Introduction to Self-Adaptive Systems: A Contemporary Software Engineering Perspective. Hoboken, Wiley-IEEE Press, 2020. DOI 10.1002/9781119574910',
    'ziborev': '10. Ziborev A.V. Antipatterns of building microservice applications in high-load projects. Universum: tekhnicheskie nauki [Universum: Technical Sciences], 2023, no. 11-1(116), pp. 29–34. DOI 10.32743/UniTech.2023.116.11.16204 (in Russian).',
    'astr': '15. Astrakhantseva I.A., Gerasimov A.S., Astrakhantsev R.G. Forecasting regional inflation using machine learning algorithms. Ivecofin, 2022, no. 4(54), pp. 6–13. DOI 10.6060/ivecofin.2022544.620 (in Russian).',
    'fashion': '16. Sizova O.V., Sirotkina A.S., Rychikhina N.S. Forecasting fashion trends based on neural networks. Modern science-intensive technologies. Regional supplement, 2025, no. 3(83), pp. 47–56. DOI 10.6060/snt.20258303 (in Russian).',
    'pavlova': '17. Pavlova E.A., Sizova O.V. Analysis and optimization of on-line sales of a trading organization. Ivecofin, 2024, no. 4(62), pp. 88–97. DOI 10.6060/ivecofin.2024624.706 (in Russian).',
    'kseno': '18. Ksenofontova O.L., Mirolyubova A.A., Fokin S.A. Use of data mining methods in the banking sector. Modern science-intensive technologies. Regional supplement, 2023, no. 4(76), pp. 76–83. DOI 10.6060/snt.20237604.00010 (in Russian).',
}
RENUM = {4: 5, 7: 6, 8: 7, 13: 9, 19: 11, 21: 12, 23: 13, 25: 14}
DROP = {3, 5, 6, 9, 10, 11, 12, 14, 15, 16, 17, 18, 20, 22, 24}
INSERT_AFTER = {2: ['glumov', 'karpovich'], 12: ['weyns'], 14: ['ziborev'],
                25: ['astr', 'fashion', 'pavlova', 'kseno']}


def entries(heading, stop):
    ps = d.paragraphs()
    h = [p for p in ps if d.ptext(p).strip() == heading][0]
    out, started = [], False
    for p in ps[ps.index(h) + 1:]:
        t = d.ptext(p).strip()
        if stop and t == stop:
            break
        if t:
            out.append(p)
    return out


def fix_list(heading, stop, new):
    items = entries(heading, stop)
    by_num = {}
    extra = []
    for p in items:
        t = d.ptext(p).strip()
        n = int(t.split('.')[0])
        if n in by_num:
            raise SystemExit('duplicate number %d' % n)
        by_num[n] = p
    template = by_num[1]
    for n in sorted(by_num):
        p = by_num[n]
        if n in RENUM:
            d.renumber(p, n, RENUM[n])
        elif n in DROP or n > 25:          # >25: the teacher's green candidates
            d.delete_paragraph(p)
    for n, keys in INSERT_AFTER.items():
        ref = by_num[n]
        for k in keys:
            ref = d.insert_paragraph_after(ref, new[k], template)


fix_list('ЛИТЕРАТУРА', 'REFERENCES', RU_NEW)
fix_list('REFERENCES', None, EN_NEW)
d.save()
print('applied')
