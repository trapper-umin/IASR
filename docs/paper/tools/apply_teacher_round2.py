# -*- coding: utf-8 -*-
"""Round 2 of the co-author's review: references only, 20 sources, all 2019+.

Authors' data are left untouched on request. Every change is a tracked
revision; text is changed only where a citation is added, removed or
renumbered, or where a sentence relied on a source that had to go.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from track import Doc

d = Doc(sys.argv[1])
P = d.find

# ================================================================ body
p = P('в литературе их принято называть мягкими ресурсами')
d.insert_after(p, 'при избыточном выделении мягких ресурсов [1].',
               ' Роль мягких ресурсов в оптимизации параллельной обработки данных и ограниченность их статической настройки рассматривались авторами ранее [3].')

p = P('виртуальные потоки платформы Java')
d.replace(p, ' [3],', ', а асинхронная и событийно-ориентированная обработка повышает масштабируемость высоконагруженных систем [4, 5],')
d.replace(p, '[4]', '[6]')

p = P('Библиотека Netflix concurrency-limits')
d.replace(p, ' [5]', '')
d.replace(p, ' [6].', '.')
d.replace(p, ' [7]', '')
d.replace(p, ' [8],', ',')
d.insert_after(p, 'ограничивают конкурентность статическим семафором.',
               ' В исследованиях управление перегрузкой также строится на ограничении допуска: серверная схема выдачи разрешений по задержке очереди позволяет выдерживать всплески нагрузки без коллапса пропускной способности [7].')

p = P('Программные системы с обратной связью рассматриваются')
d.replace(p, 'Адаптация сложных систем как самостоятельный предмет исследования разработана в отечественной литературе [9]. ', '')
d.replace(p, ' [10]', '')
d.replace(p, ' вычислительными системами [11]. Разрабатываются методы синтеза регуляторов программных систем с формальными гарантиями [12].',
          ', включая синтез регуляторов с формальными гарантиями [8].')
d.replace(p, '[13]', '[9]')

p = P('Однорессурсный регулятор такого переноса не наблюдает')
d.replace(p, 'СУБД и соединения с ней нередко оказываются узким местом, препятствующим линейному масштабированию распределённых систем [14].',
          'база данных и соединения с ней нередко становятся источником проблем: избыточный пул соединений вызывает всплески времени отклика, которые горизонтальное масштабирование не устраняет [2], а общая для нескольких сервисов база данных может приводить к каскадным сбоям [10]. Ограничивающим ресурсом может оказаться и точка конкуренции внутри приложения, например блокировка: она снижает пропускную способность и ухудшает хвост задержки даже при недогруженном процессоре, и привычных сигналов перегрузки для её обнаружения недостаточно [11].')

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
d.replace(p, '[19]', '[12]')

p = P('Положение максимума совпало с числом выделенных ядер')
d.replace(p, ', – что согласуется с моделью сервера баз данных как многоканальной системы массового обслуживания, число каналов которой определяется числом процессоров [20].', '.')
d.replace(p, 'Практическая рекомендация для пулов соединений также связывает их размер с числом ядер сервера баз данных, предлагая оценку «удвоенное число ядер плюс число дисков», рассчитанную на запросы с ожиданием ввода-вывода [21]',
          'Зависимость оптимальной конкурентности от числа выделенных ядер отмечается и в исследованиях микросервисов: при увеличении лимита процессора с двух до четырёх ядер оптимальный размер пула потоков вырос с 10 до 30 [2]')

p = P('Порядок измерений потребовал отдельного внимания.')
d.insert_after(p, 'Порядок измерений потребовал отдельного внимания.',
               ' Ошибки в организации бенчмарков на платформе JVM заметно искажают результаты измерений [13], а измерения производительности в облачной среде отличаются высокой изменчивостью [14].')

p = P('Достоверность оценивалась бутстрэпом')
d.replace(p, ' [22]:', ':')

p = P('является чистой ошибкой измерения')
d.insert_after(p, 'является чистой ошибкой измерения',
               ' – аналог A/A-тестов, которыми проверяют надёжность стендов для измерения производительности [14]')

p = P('service mesh – по экспериментальным данным')
d.replace(p, '[23]', '[15]')

p = P('Направлением дальнейших исследований')
d.replace(p, 'Такой переход соответствует классической постановке адаптации и обучения в автоматических системах [24], а методы машинного обучения уже применяются для прогнозирования трафика информационных ресурсов [25],',
          'Методы машинного обучения уже применяются для прогнозирования в самых разных предметных областях – трафика информационных ресурсов [16], региональной инфляции [17], модных трендов [18], а методы анализа данных – для оптимизации онлайн-продаж [19] и в банковской сфере [20],')

# ================================================================ reference lists
RU = {
 't6': '3. Бобков, А.Н. Интеллектуальная адаптация мягких ресурсов как фактор оптимизации параллельной обработки данных в современных информационных системах / А.Н. Бобков, О.В. Сизова // Проблемы экономики, финансов и управления производством : сб. науч. тр. вузов России. – Иваново: ИГХТУ, 2026. – Вып. 58. – С. 131–134.',
 'glumov': '4. Глумов, К.С. Преимущества реактивного подхода в высоконагруженных микросервисных системах / К.С. Глумов // Вестник науки. – 2024. – № 10(79). – С. 410–426.',
 'karpovich': '5. Карпович, М.Н. Особенности проектирования микросервисно-событийных архитектур для высоконагруженных распределенных систем обработки информаций / М.Н. Карпович // Труды БГТУ. Сер. 3, Физико-математические науки и информатика. – 2023. – № 1(266). – С. 89–95. – DOI 10.52065/2520-6141-2023-266-1-15',
 'breakwater': '7. Cho I., Saeed A., Fried J., Park S.J., Alizadeh M., Belay A. Overload Control for µs-scale RPCs with Breakwater // 14th USENIX Symposium on Operating Systems Design and Implementation (OSDI 20). – 2020. – P. 299–314.',
 'weyns': '8. Weyns D. An Introduction to Self-Adaptive Systems: A Contemporary Software Engineering Perspective. – Hoboken: Wiley-IEEE Press, 2020. – DOI 10.1002/9781119574910',
 'ziborev': '10. Зиборев, А.В. Антипаттерны построения микросервисных приложений в высоконагруженных проектах / А.В. Зиборев // Universum: технические науки. – 2023. – № 11-1(116). – С. 29–34. – DOI 10.32743/UniTech.2023.116.11.16204',
 'protego': '11. Cho I., Saeed A., Park S.J., Alizadeh M., Belay A. Protego: Overload Control for Applications with Unpredictable Lock Contention // 20th USENIX Symposium on Networked Systems Design and Implementation (NSDI 23). – 2023. – P. 725–738.',
 'costa': '13. Costa D., Bezemer C.-P., Leitner P., Andrzejak A. What’s Wrong with My Benchmark Results? Studying Bad Practices in JMH Benchmarks // IEEE Transactions on Software Engineering. – 2021. – Vol. 47, No. 7. – P. 1452–1467. – DOI 10.1109/TSE.2019.2925345',
 'laaber': '14. Laaber C., Scheuner J., Leitner P. Software microbenchmarking in the cloud. How bad is it really? // Empirical Software Engineering. – 2019. – Vol. 24, No. 4. – P. 2469–2508. – DOI 10.1007/s10664-019-09681-1',
 'astr': '17. Астраханцева, И.А. Прогнозирование региональной инфляции с помощью алгоритмов машинного обучения / И.А. Астраханцева, А.С. Герасимов, Р.Г. Астраханцев // Известия высших учебных заведений. Серия: Экономика, финансы и управление производством. – 2022. – № 4(54). – С. 6–13. – DOI 10.6060/ivecofin.2022544.620. – EDN ITYDFE.',
 'fashion': '18. Сизова, О.В. Прогнозирование модных трендов на основе нейронных сетей / О.В. Сизова, А.С. Сироткина, Н.С. Рычихина // Современные наукоёмкие технологии. Региональное приложение. – 2025. – № 3(83). – С. 47–56. – DOI 10.6060/snt.20258303',
 'pavlova': '19. Павлова, Е.А. Анализ и оптимизация on-line продаж торговой организации / Е.А. Павлова, О.В. Сизова // Известия высших учебных заведений. Серия: Экономика, финансы и управление производством. – 2024. – № 4(62). – С. 88–97. – DOI 10.6060/ivecofin.2024624.706',
 'kseno': '20. Ксенофонтова, О.Л. Использование методов интеллектуального анализа данных в банковской сфере / О.Л. Ксенофонтова, А.А. Миролюбова, С.А. Фокин // Современные наукоёмкие технологии. Региональное приложение. – 2023. – № 4(76). – С. 76–83. – DOI 10.6060/snt.20237604.00010',
}
EN = {
 't6': '3. Bobkov A.N., Sizova O.V. Intelligent adaptation of soft resources as a factor in optimizing parallel data processing in modern information systems. Problemy ekonomiki, finansov i upravleniya proizvodstvom [Problems of Economics, Finance and Production Management]: collection of scientific papers of Russian universities, issue 58. Ivanovo, ISUCT, 2026, pp. 131–134 (in Russian).',
 'glumov': '4. Glumov K.S. Advantages of the reactive approach in high-load microservice systems. Vestnik nauki [Bulletin of Science], 2024, no. 10(79), pp. 410–426 (in Russian).',
 'karpovich': '5. Karpovich M.N. Features of designing microservice-event architectures for highly loaded distributed information processing systems. Proceedings of BSTU, issue 3, Physics and Mathematics. Informatics, 2023, no. 1(266), pp. 89–95. DOI 10.52065/2520-6141-2023-266-1-15 (in Russian).',
 'breakwater': '7. Cho I., Saeed A., Fried J., Park S.J., Alizadeh M., Belay A. Overload Control for µs-scale RPCs with Breakwater. Proceedings of the 14th USENIX Symposium on Operating Systems Design and Implementation (OSDI 20), 2020, pp. 299–314.',
 'weyns': '8. Weyns D. An Introduction to Self-Adaptive Systems: A Contemporary Software Engineering Perspective. Hoboken, Wiley-IEEE Press, 2020. DOI 10.1002/9781119574910',
 'ziborev': '10. Ziborev A.V. Antipatterns of building microservice applications in high-load projects. Universum: tekhnicheskie nauki [Universum: Technical Sciences], 2023, no. 11-1(116), pp. 29–34. DOI 10.32743/UniTech.2023.116.11.16204 (in Russian).',
 'protego': '11. Cho I., Saeed A., Park S.J., Alizadeh M., Belay A. Protego: Overload Control for Applications with Unpredictable Lock Contention. Proceedings of the 20th USENIX Symposium on Networked Systems Design and Implementation (NSDI 23), 2023, pp. 725–738.',
 'costa': '13. Costa D., Bezemer C.-P., Leitner P., Andrzejak A. What’s Wrong with My Benchmark Results? Studying Bad Practices in JMH Benchmarks. IEEE Transactions on Software Engineering, 2021, vol. 47, no. 7, pp. 1452–1467. DOI 10.1109/TSE.2019.2925345',
 'laaber': '14. Laaber C., Scheuner J., Leitner P. Software microbenchmarking in the cloud. How bad is it really? Empirical Software Engineering, 2019, vol. 24, no. 4, pp. 2469–2508. DOI 10.1007/s10664-019-09681-1',
 'astr': '17. Astrakhantseva I.A., Gerasimov A.S., Astrakhantsev R.G. Forecasting regional inflation using machine learning algorithms. Ivecofin, 2022, no. 4(54), pp. 6–13. DOI 10.6060/ivecofin.2022544.620 (in Russian).',
 'fashion': '18. Sizova O.V., Sirotkina A.S., Rychikhina N.S. Forecasting fashion trends based on neural networks. Modern science-intensive technologies. Regional supplement, 2025, no. 3(83), pp. 47–56. DOI 10.6060/snt.20258303 (in Russian).',
 'pavlova': '19. Pavlova E.A., Sizova O.V. Analysis and optimization of on-line sales of a trading organization. Ivecofin, 2024, no. 4(62), pp. 88–97. DOI 10.6060/ivecofin.2024624.706 (in Russian).',
 'kseno': '20. Ksenofontova O.L., Mirolyubova A.A., Fokin S.A. Use of data mining methods in the banking sector. Modern science-intensive technologies. Regional supplement, 2023, no. 4(76), pp. 76–83. DOI 10.6060/snt.20237604.00010 (in Russian).',
}
RENUM = {4: 6, 13: 9, 19: 12, 23: 15, 25: 16}
DROP = {3, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 16, 17, 18, 20, 21, 22, 24}
INSERT_AFTER = {2: ['t6', 'glumov', 'karpovich'], 8: ['breakwater'], 12: ['weyns'],
                14: ['ziborev', 'protego'], 20: ['costa', 'laaber'],
                25: ['astr', 'fashion', 'pavlova', 'kseno']}


def entries(heading, stop):
    ps = d.paragraphs()
    h = [p for p in ps if d.ptext(p).strip() == heading][0]
    out = []
    for p in ps[ps.index(h) + 1:]:
        t = d.ptext(p).strip()
        if stop and t == stop:
            break
        if t:
            out.append(p)
    return out


def fix_list(heading, stop, new):
    by_num = {}
    for p in entries(heading, stop):
        n = int(d.ptext(p).strip().split('.')[0])
        assert n not in by_num, n
        by_num[n] = p
    template = by_num[1]
    for n in sorted(by_num):
        if n in RENUM:
            d.renumber(by_num[n], n, RENUM[n])
        elif n in DROP or n > 25:
            d.delete_paragraph(by_num[n])
    for n, keys in INSERT_AFTER.items():
        ref = by_num[n]
        for k in keys:
            ref = d.insert_paragraph_after(ref, new[k], template)


fix_list('ЛИТЕРАТУРА', 'REFERENCES', RU)
fix_list('REFERENCES', None, EN)
d.save()
print('applied')
