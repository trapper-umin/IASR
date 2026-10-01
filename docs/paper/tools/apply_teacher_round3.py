# -*- coding: utf-8 -*-
"""Round 3: five more 2019+ sources, woven into the text (25 in total).

Runs on the round-2 document after it has been baked (its additions already
highlighted), so round-2 and round-3 additions end up in the same colour.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from track import Doc

d = Doc(sys.argv[1])
P = d.find

# ---------------------------------------------------------------- body
p = P('включая синтез регуляторов с формальными гарантиями')
d.insert_after(p, 'включая синтез регуляторов с формальными гарантиями [8].',
               ' Идеи теории управления применяются и в горизонтальном масштабировании: число экземпляров микросервисов регулируется ПИД-регулятором с учётом зависимостей между сервисами [9].')
d.replace(p, '[9]', '[10, 11]')                    # ConScale: conference + journal version

p = P('общая для нескольких сервисов база данных')
d.replace(p, '[10]', '[12]')
d.replace(p, '[11]', '[13]')

p = P('Критерий оптимизации')
d.insert_after(p, 'и при допустимых границах изменения каждого ресурса.',
               ' В микросервисных системах сквозной SLO к тому же приходится распределять между отдельными сервисами, и само это распределение решается как задача оптимизации, например градиентным спуском [14].')

p = P('Нагрузочный стенд построен вокруг приложения')
d.insert_after(p, 'и выполняет короткую работу в самом приложении.',
               ' В отличие от открытых наборов эталонных микросервисных приложений [15], стенд намеренно сведён к одной операции, чтобы связь между мягкими ресурсами и результатом не маскировалась сложностью приложения.')

p = P('Имитационное моделирование широко применяется')
d.replace(p, '[12]', '[16]')

p = P('Порядок измерений потребовал отдельного внимания.')
d.replace(p, '[13]', '[17]')
d.replace(p, '[14]', '[18]')

p = P('аналог A/A-тестов')
d.replace(p, '[14]', '[18]')

p = P('service mesh – по экспериментальным данным')
d.replace(p, '[15]', '[19]')

p = P('Направлением дальнейших исследований')
d.insert_after(p, 'замена подобранных коэффициентов обучаемой политикой управления.',
               ' Для микросервисов такой подход уже реализован на уровне аппаратных ресурсов: методы машинного обучения по телеметрии выявляют источники нарушений SLO и перераспределяют ресурсы, сокращая число нарушений до 16 раз [20].')
for old, new in ((16, 21), (17, 22), (18, 23), (19, 24), (20, 25)):
    d.replace(p, '[%d]' % old, '[%d]' % new)

# ---------------------------------------------------------------- reference lists
RU = {
 'showar': '9. Baarzi A.F., Kesidis G. SHOWAR: Right-Sizing and Efficient Scheduling of Microservices // Proceedings of the ACM Symposium on Cloud Computing (SoCC ’21). – 2021. – P. 427–441. – DOI 10.1145/3472883.3486999',
 'tpds': '11. Liu J., Zhang S., Wang Q., Wei J. Coordinating Fast Concurrency Adapting With Autoscaling for SLO-Oriented Web Applications // IEEE Transactions on Parallel and Distributed Systems. – 2022. – Vol. 33, No. 12. – P. 3349–3362. – DOI 10.1109/TPDS.2022.3151512',
 'parslo': '14. Mirhosseini A., Elnikety S., Wenisch T.F. Parslo: A Gradient Descent-based Approach for Near-optimal Partial SLO Allotment in Microservices // Proceedings of the ACM Symposium on Cloud Computing (SoCC ’21). – 2021. – P. 442–457. – DOI 10.1145/3472883.3486985',
 'dsb': '15. Gan Y., Zhang Y., Cheng D., Shetty A., Rathi P., Katarki N., Bruno A., Hu J., Ritchken B., Jackson B., et al. An Open-Source Benchmark Suite for Microservices and Their Hardware-Software Implications for Cloud & Edge Systems // Proceedings of the 24th International Conference on Architectural Support for Programming Languages and Operating Systems (ASPLOS ’19). – 2019. – P. 3–18. – DOI 10.1145/3297858.3304013',
 'firm': '20. Qiu H., Banerjee S.S., Jha S., Kalbarczyk Z.T., Iyer R.K. FIRM: An Intelligent Fine-grained Resource Management Framework for SLO-Oriented Microservices // 14th USENIX Symposium on Operating Systems Design and Implementation (OSDI 20). – 2020. – P. 805–825.',
}
EN = {
 'showar': '9. Baarzi A.F., Kesidis G. SHOWAR: Right-Sizing and Efficient Scheduling of Microservices. Proceedings of the ACM Symposium on Cloud Computing (SoCC ’21), 2021, pp. 427–441. DOI 10.1145/3472883.3486999',
 'tpds': '11. Liu J., Zhang S., Wang Q., Wei J. Coordinating Fast Concurrency Adapting With Autoscaling for SLO-Oriented Web Applications. IEEE Transactions on Parallel and Distributed Systems, 2022, vol. 33, no. 12, pp. 3349–3362. DOI 10.1109/TPDS.2022.3151512',
 'parslo': '14. Mirhosseini A., Elnikety S., Wenisch T.F. Parslo: A Gradient Descent-based Approach for Near-optimal Partial SLO Allotment in Microservices. Proceedings of the ACM Symposium on Cloud Computing (SoCC ’21), 2021, pp. 442–457. DOI 10.1145/3472883.3486985',
 'dsb': '15. Gan Y., Zhang Y., Cheng D., Shetty A., Rathi P., Katarki N., Bruno A., Hu J., Ritchken B., Jackson B., et al. An Open-Source Benchmark Suite for Microservices and Their Hardware-Software Implications for Cloud & Edge Systems. Proceedings of the 24th International Conference on Architectural Support for Programming Languages and Operating Systems (ASPLOS ’19), 2019, pp. 3–18. DOI 10.1145/3297858.3304013',
 'firm': '20. Qiu H., Banerjee S.S., Jha S., Kalbarczyk Z.T., Iyer R.K. FIRM: An Intelligent Fine-grained Resource Management Framework for SLO-Oriented Microservices. Proceedings of the 14th USENIX Symposium on Operating Systems Design and Implementation (OSDI 20), 2020, pp. 805–825.',
}
RENUM = {9: 10, 10: 12, 11: 13, 12: 16, 13: 17, 14: 18, 15: 19,
         16: 21, 17: 22, 18: 23, 19: 24, 20: 25}
INSERT_AFTER = {8: ['showar'], 9: ['tpds'], 11: ['parslo', 'dsb'], 15: ['firm']}


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
    assert sorted(by_num) == list(range(1, 21)), sorted(by_num)
    template = by_num[1]
    for n in sorted(by_num, reverse=True):
        if n in RENUM:
            d.renumber(by_num[n], n, RENUM[n])
    for n, keys in INSERT_AFTER.items():
        ref = by_num[n]
        for k in keys:
            ref = d.insert_paragraph_after(ref, new[k], template)


fix_list('ЛИТЕРАТУРА', 'REFERENCES', RU)
fix_list('REFERENCES', None, EN)
d.save()
print('applied')
